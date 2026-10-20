package gag.nails.files;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.Actor;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import gag.nails.testsupport.MutableClock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Тесты хранилища на настоящих транзакциях: наследник ставит на себя {@code @Transactional(propagation =
 * NOT_SUPPORTED)} прямо на классе (объявленная на классе аннотация сильнее мета-аннотации {@code @ModuleTest}).
 * Удаление файлов после фиксации и уборку иначе не проверить.
 *
 * <p>Каталог хранилища — общий тестовый ({@code nails-gag.files.root}: при прогоне Gradle — {@code
 * build/test-files/<задача>} модуля, только для тестов этого модуля). Тесты смотрят, что осталось на диске, и после
 * каждого теста очищают и его, и таблицу.
 */
@TestPropertySource(
        properties = {
            "nails-gag.files.max-file-bytes=" + FilesTestSupport.MAX_FILE_BYTES,
            "nails-gag.files.max-concurrent-processing=" + FilesTestSupport.PROCESSING_SLOTS,
            "nails-gag.files.processing-wait=200ms"
        })
abstract class FilesTestSupport {

    /** Лимит размера файла в тестах — 1 МиБ, чтобы проверка лимита не гоняла 15 МБ. */
    static final long MAX_FILE_BYTES = 1_048_576;

    static final int PROCESSING_SLOTS = 2;

    static final UUID ACCOUNT = UUID.fromString("0198a1f0-0000-7000-8000-000000000001");
    static final UUID OTHER_ACCOUNT = UUID.fromString("0198a1f0-0000-7000-8000-000000000002");

    /** Загружает клиент со своим аккаунтом; права на назначение проверяет HTTP-слой, модулю нужен только аккаунт. */
    static final Actor UPLOADER = Actor.of(ACCOUNT, UUID.fromString("0198a1f0-0000-7000-8000-0000000000c1"), Map.of());

    @Autowired
    FileStorage storage;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    /** Корень хранилища — тот же, что у приложения теста. */
    Path root;

    @Value("${nails-gag.files.root}")
    void setRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @AfterEach
    void removeStoredFiles() throws IOException {
        jdbc.sql("delete from files.stored_file").update();
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.delete(path);
                }
            }
        }
    }

    StoredFileView store(byte[] content, FilePurpose purpose) {
        return storage.store(new ByteArrayInputStream(content), content.length, purpose, UPLOADER);
    }

    Path directory(StoredFileView file) {
        return root.resolve(file.purpose().name().toLowerCase(Locale.ROOT))
                .resolve(file.id().toString());
    }

    /** Все обычные файлы под корнем хранилища, включая временные. */
    List<Path> filesOnDisk() {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int rows() {
        return jdbc.sql("select count(*) from files.stored_file")
                .query(Integer.class)
                .single();
    }

    void assertNothingStored() {
        assertThat(filesOnDisk()).isEmpty();
        assertThat(rows()).isZero();
    }
}
