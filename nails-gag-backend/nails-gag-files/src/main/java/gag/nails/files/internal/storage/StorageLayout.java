package gag.nails.files.internal.storage;

import gag.nails.common.error.InternalException;
import gag.nails.files.model.FilePurpose;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Раскладка хранилища на диске:
 *
 * <pre>
 * &lt;root&gt;/portfolio/&lt;id&gt;/480.jpg, 960.jpg, 1600.jpg
 * &lt;root&gt;/reference/&lt;id&gt;/2000.jpg
 * &lt;root&gt;/.tmp/                  незавершённые загрузки
 * </pre>
 *
 * Временный каталог лежит внутри корня: готовый каталог файла переносится на место одной атомарной операцией, а она
 * возможна только в пределах одной файловой системы. Каталоги создаются по мере надобности, а не при старте: в тестах
 * приложения, которые ничего не загружают, корня может и не быть.
 */
@Component
class StorageLayout {

    private static final Logger log = LoggerFactory.getLogger(StorageLayout.class);

    private static final String TEMPORARY = ".tmp";

    private final Path root;

    StorageLayout(FileProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    Path purposeDirectory(FilePurpose purpose) {
        return root.resolve(purpose.name().toLowerCase(Locale.ROOT));
    }

    /** Каталог вариантов файла. */
    Path directory(FilePurpose purpose, UUID fileId) {
        return purposeDirectory(purpose).resolve(fileId.toString());
    }

    Path variant(FilePurpose purpose, UUID fileId, int width) {
        return directory(purpose, fileId).resolve(width + ".jpg");
    }

    Path temporaryDirectory() {
        return root.resolve(TEMPORARY);
    }

    /** Каталог незавершённых загрузок, созданный, если его ещё нет. */
    Path createTemporaryDirectory() {
        return createDirectories(temporaryDirectory());
    }

    /** Каталог назначения, созданный, если его ещё нет. */
    Path createPurposeDirectory(FilePurpose purpose) {
        return createDirectories(purposeDirectory(purpose));
    }

    /**
     * Удаляет файл или каталог со всем содержимым; чего уже нет — пропускает. Символьные ссылки удаляются сами, без
     * перехода по ним.
     */
    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException e) throws IOException {
                if (e != null && !(e instanceof NoSuchFileException)) {
                    throw e;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * {@link #deleteRecursively} без исключения: сбой пишется в лог, остатки добьёт сверка диска с базой
     * ({@code FileMaintenance.removeStrayDirectories}). Для уборки после ошибки и после фиксации, когда бросать уже
     * некуда.
     */
    static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            log.warn("cannot delete {}, stray files will be removed by the cleanup job", path, e);
        }
    }

    private static Path createDirectories(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new InternalException("cannot create file storage directory " + directory, e);
        }
    }
}
