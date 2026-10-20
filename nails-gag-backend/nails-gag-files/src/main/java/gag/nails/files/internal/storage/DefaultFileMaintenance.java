package gag.nails.files.internal.storage;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Уборка хранилища. Возраст каталогов и временных файлов на диске — по времени их изменения, сравнивается с часами
 * приложения. Сутки запаса берегут загрузку, которая прямо сейчас перенесла каталог на место, но ещё не
 * зафиксировала строку.
 */
@Service
class DefaultFileMaintenance implements FileMaintenance {

    /** Сколько «сирота» ждёт привязки и сколько живут каталог без строки и остатки загрузки. */
    static final Duration GRACE_PERIOD = Duration.ofDays(1);

    /** Сколько «сирот» удаляется одной транзакцией и сколько идентификаторов сверяется с базой одним запросом. */
    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(DefaultFileMaintenance.class);

    private final StoredFileRepository files;
    private final TransactionTemplate transaction;
    private final StorageLayout layout;
    private final Clock clock;
    private final int batchSize;

    @Autowired
    DefaultFileMaintenance(
            StoredFileRepository files, TransactionTemplate transaction, StorageLayout layout, Clock clock) {
        this(files, transaction, layout, clock, BATCH_SIZE);
    }

    /** С другим размером пачки — для теста нескольких пачек. */
    DefaultFileMaintenance(
            StoredFileRepository files,
            TransactionTemplate transaction,
            StorageLayout layout,
            Clock clock,
            int batchSize) {
        this.files = files;
        this.transaction = transaction;
        this.layout = layout;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Пачками (по умолчанию по {@value #BATCH_SIZE}), от старых к новым, каждая — в своей транзакции: блокировки строк держатся
     * недолго, а файлы пачки стираются сразу после её фиксации. Идёт, пока очередная пачка не окажется пустой.
     */
    @Override
    public int removeOrphans() {
        var cutoff = clock.instant().minus(GRACE_PERIOD);
        var removed = 0;
        int batch;
        do {
            batch = transaction.execute(status -> removeOrphanBatch(cutoff));
            removed += batch;
        } while (batch > 0);
        return removed;
    }

    private int removeOrphanBatch(Instant cutoff) {
        var removed = files.deleteOrphans(cutoff, batchSize);
        if (!removed.isEmpty()) {
            var directories = removed.stream()
                    .map(file -> layout.directory(file.getPurpose(), file.getId()))
                    .toList();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    directories.forEach(StorageLayout::deleteQuietly);
                }
            });
        }
        return removed.size();
    }

    /**
     * Без транзакции: сверка долго ходит по диску, а базе нужны лишь короткие запросы «какие из этих есть». Сбой
     * чтения одного каталога пишется в лог и не мешает остальным.
     */
    @Override
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
        for (var from = 0; from < ids.size(); from += batchSize) {
            var batch = ids.subList(from, Math.min(from + batchSize, ids.size()));
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
            log.warn("cannot list file storage directory {}, skipping it this run", directory, e);
            return stale;
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
            log.warn("cannot read modification time of {}, skipping it this run", entry, e);
            return false;
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
