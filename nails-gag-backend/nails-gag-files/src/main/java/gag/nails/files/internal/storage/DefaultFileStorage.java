package gag.nails.files.internal.storage;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.FileStorage;
import gag.nails.files.error.TooLargeFileException;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.FileVariant;
import gag.nails.files.model.StoredFileView;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Хранение на диске по раскладке {@link StorageLayout}. Загрузка идёт так (спецификация 3.2): поток — во временный
 * файл с подсчётом байт и SHA-256, обработка — на одном из мест {@link ProcessingSlots}, варианты — во временный
 * каталог и одним атомарным переносом на место, затем строка. Обработка — вне транзакции: соединение с базой не
 * держится, пока картинка ждёт места и обрабатывается.
 */
@Service
class DefaultFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(DefaultFileStorage.class);

    private static final String CONTENT_TYPE = "image/jpeg";
    private static final int BUFFER_BYTES = 64 * 1024;

    private final StoredFileRepository files;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final StorageLayout layout;
    private final ProcessingSlots slots;
    private final ImageProcessor processor;
    private final Clock clock;
    private final long maxFileBytes;

    DefaultFileStorage(
            StoredFileRepository files,
            EntityManager entityManager,
            TransactionTemplate transaction,
            StorageLayout layout,
            ProcessingSlots slots,
            FileProperties properties,
            Clock clock) {
        this.files = files;
        this.entityManager = entityManager;
        this.transaction = transaction;
        this.layout = layout;
        this.slots = slots;
        this.processor = new ImageProcessor(properties.maxPixels());
        this.clock = clock;
        this.maxFileBytes = properties.maxFileBytes();
    }

    @Override
    public StoredFileView store(InputStream content, long sizeBytes, FilePurpose purpose, Actor actor) {
        var uploadedBy = actor.accountId();
        if (uploadedBy == null) {
            throw new AccessDeniedException();
        }
        if (sizeBytes > maxFileBytes) {
            throw new TooLargeFileException(maxFileBytes);
        }
        var id = UUID.randomUUID();
        var temporary = layout.createTemporaryDirectory();
        var upload = temporary.resolve(id + ".upload");
        var staging = temporary.resolve(id.toString());
        try {
            var received = receive(content, upload);
            var slot = slots.acquire();
            ProcessedImage image;
            try {
                image = processor.process(upload, purpose);
            } finally {
                slot.close();
            }
            layout.createPurposeDirectory(purpose);
            var directory = place(image, staging, layout.directory(purpose, id));
            var file = new StoredFileEntity(
                    id,
                    purpose,
                    image.width(),
                    image.height(),
                    received.sha256(),
                    received.size(),
                    uploadedBy,
                    clock.instant());
            try {
                return transaction.execute(status -> insert(file, directory));
            } catch (RuntimeException e) {
                StorageLayout.deleteQuietly(directory);
                throw e;
            }
        } finally {
            StorageLayout.deleteQuietly(upload);
            StorageLayout.deleteQuietly(staging);
        }
    }

    @Override
    @Transactional
    public void attach(UUID fileId, FilePurpose purpose, UUID uploadedBy) {
        if (files.attach(fileId, purpose, uploadedBy, clock.instant()) == 1) {
            return;
        }
        // Назначение и загрузивший не меняются, поэтому причину можно взять и из закэшированной копии строки.
        var file = files.findById(fileId)
                .filter(found -> found.uploadedBy().equals(uploadedBy))
                .orElseThrow(() -> new ValidationException("fileId", "no such file uploaded by this account"));
        if (file.purpose() != purpose) {
            throw new ValidationException("fileId", "must be a " + purpose + " file");
        }
        throw new ValidationException("fileId", "already in use");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFileView> find(UUID fileId) {
        return files.findById(fileId).map(StoredFileEntity::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileVariant> open(UUID fileId, int width) {
        return files.findById(fileId)
                .filter(file -> file.purpose().widths().contains(width))
                .flatMap(file -> openVariant(layout.variant(file.purpose(), fileId, width)));
    }

    @Override
    @Transactional
    public void delete(UUID fileId) {
        var file = files.findById(fileId);
        if (file.isEmpty()) {
            return;
        }
        files.delete(file.get());
        var directory = layout.directory(file.get().purpose(), fileId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                StorageLayout.deleteQuietly(directory);
            }
        });
    }

    /**
     * Строка в транзакции вызывающего или своей. Уборка файлов при откате регистрируется до вставки: откатится ли
     * вставка или вся транзакция вызывающего позже — каталог удалится. При неизвестном исходе фиксации каталог
     * остаётся: строка могла сохраниться, а лишний каталог уберёт сверка.
     */
    private StoredFileView insert(StoredFileEntity file, Path directory) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    StorageLayout.deleteQuietly(directory);
                }
            }
        });
        entityManager.persist(file);
        entityManager.flush();
        return file.toView();
    }

    /** Копирует поток во временный файл, считая байты и SHA-256; лишний байт сверх лимита прерывает копирование. */
    private Received receive(InputStream content, Path upload) {
        var digest = sha256();
        var size = 0L;
        try (var out = Files.newOutputStream(upload, StandardOpenOption.CREATE_NEW)) {
            var buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = content.read(buffer)) != -1) {
                size += read;
                if (size > maxFileBytes) {
                    throw new TooLargeFileException(maxFileBytes);
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new InternalException("cannot receive uploaded file", e);
        }
        return new Received(size, HexFormat.of().formatHex(digest.digest()));
    }

    /** Пишет варианты во временный каталог и переносит его на место одной атомарной операцией. */
    private static Path place(ProcessedImage image, Path staging, Path target) {
        try {
            Files.createDirectory(staging);
            for (var variant : image.variants().entrySet()) {
                Files.write(
                        staging.resolve(variant.getKey() + ".jpg"), variant.getValue(), StandardOpenOption.CREATE_NEW);
            }
            return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new InternalException("cannot write stored file " + target, e);
        }
    }

    /**
     * Канал открывается раньше, чем берётся размер: удаление файла после открытия (POSIX) не мешает дочитать его. Нет
     * файла при живой строке — расхождение диска с базой: отдавать нечего, это видно в логе.
     */
    private static Optional<FileVariant> openVariant(Path path) {
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            log.warn("stored file variant {} is missing on disk", path);
            return Optional.empty();
        } catch (IOException e) {
            throw new InternalException("cannot open stored file variant " + path, e);
        }
        try {
            return Optional.of(new FileVariant(Channels.newInputStream(channel), channel.size(), CONTENT_TYPE));
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new InternalException("cannot open stored file variant " + path, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalException("SHA-256 is not available", e);
        }
    }

    private record Received(long size, String sha256) {}
}
