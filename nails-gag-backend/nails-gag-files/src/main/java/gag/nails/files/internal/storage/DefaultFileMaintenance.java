package gag.nails.files.internal.storage;

import gag.nails.common.error.InternalException;
import gag.nails.files.FileMaintenance;
import gag.nails.files.model.FilePurpose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Уборка хранилища. Возраст каталогов и временных файлов на диске — по времени их изменения, сравнивается с часами
 * приложения. Сутки запаса берегут загрузку, которая прямо сейчас перенесла каталог на место, но ещё не
 * зафиксировала строку.
 */
@Service
class DefaultFileMaintenance implements FileMaintenance {

    /** Сколько «сирота» ждёт привязки и сколько живут каталог без строки и остатки загрузки. */
    static final Duration GRACE_PERIOD = Duration.ofDays(1);

    /** Сколько идентификаторов сверяется с базой одним запросом. */
    private static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(DefaultFileMaintenance.class);

    private final StoredFileRepository files;
    private final StorageLayout layout;
    private final Clock clock;

    DefaultFileMaintenance(StoredFileRepository files, StorageLayout layout, Clock clock) {
        this.files = files;
        this.layout = layout;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int removeOrphans() {
        var orphans = files.lockOrphansCreatedBefore(clock.instant().minus(GRACE_PERIOD));
        if (orphans.isEmpty()) {
            return 0;
        }
        files.deleteAll(orphans);
        var directories = orphans.stream()
                .map(orphan -> layout.directory(orphan.purpose(), orphan.id()))
                .toList();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                directories.forEach(StorageLayout::deleteQuietly);
            }
        });
        return orphans.size();
    }

    @Override
    @Transactional(readOnly = true)
    public int removeStrayDirectories() {
        var cutoff = clock.instant().minus(GRACE_PERIOD);
        var removed = 0;
        for (var purpose : FilePurpose.values()) {
            removed += removeDirectoriesWithoutRows(staleFileDirectories(purpose, cutoff));
        }
        for (var leftover : staleEntries(layout.temporaryDirectory(), cutoff)) {
            removed += delete(leftover) ? 1 : 0;
        }
        return removed;
    }

    private int removeDirectoriesWithoutRows(Map<UUID, Path> directories) {
        var removed = 0;
        var ids = new ArrayList<>(directories.keySet());
        for (var from = 0; from < ids.size(); from += BATCH_SIZE) {
            var batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            var existing = new HashSet<>(files.findExistingIds(batch));
            for (var id : batch) {
                if (!existing.contains(id) && delete(directories.get(id))) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Старые каталоги файлов назначения по идентификатору. Записи, чьё имя не идентификатор в каноническом виде,
     * хранилищу не принадлежат и не трогаются.
     */
    private Map<UUID, Path> staleFileDirectories(FilePurpose purpose, Instant cutoff) {
        var directories = new HashMap<UUID, Path>();
        for (var entry : staleEntries(layout.purposeDirectory(purpose), cutoff)) {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                fileId(entry).ifPresent(id -> directories.put(id, entry));
            }
        }
        return directories;
    }

    /** Записи каталога, изменённые раньше {@code cutoff}; каталога нет — пусто. */
    private static List<Path> staleEntries(Path directory, Instant cutoff) {
        var stale = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (var entry : entries) {
                if (modifiedBefore(entry, cutoff)) {
                    stale.add(entry);
                }
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new InternalException("cannot list file storage directory " + directory, e);
        }
        return stale;
    }

    private static boolean modifiedBefore(Path entry, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                    .toInstant()
                    .isBefore(cutoff);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new InternalException("cannot read modification time of " + entry, e);
        }
    }

    private static Optional<UUID> fileId(Path directory) {
        var name = directory.getFileName().toString();
        try {
            var id = UUID.fromString(name);
            return id.toString().equals(name) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean delete(Path path) {
        try {
            StorageLayout.deleteRecursively(path);
            log.info("removed stray {}", path);
            return true;
        } catch (IOException e) {
            log.warn("cannot remove stray {}, will retry on the next run", path, e);
            return false;
        }
    }
}
