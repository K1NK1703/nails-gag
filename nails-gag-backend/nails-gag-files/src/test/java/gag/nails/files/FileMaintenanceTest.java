package gag.nails.files;

import static gag.nails.files.TestImages.png;
import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.ModuleTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileMaintenanceTest extends FilesTestSupport {

    @Autowired
    FileMaintenance maintenance;

    @Test
    void orphanOlderThanADayIsRemoved() {
        var orphan = store(png(320, 240), FilePurpose.REFERENCE);
        clock.advance(Duration.ofDays(1).plusMinutes(1));

        assertThat(maintenance.removeOrphans()).isEqualTo(1);

        assertThat(storage.find(orphan.id())).isEmpty();
        assertThat(directory(orphan)).doesNotExist();
        assertThat(maintenance.removeOrphans()).isZero();
    }

    @Test
    void attachedFileIsKept() {
        var attached = store(png(320, 240), FilePurpose.PORTFOLIO);
        storage.attach(attached.id(), FilePurpose.PORTFOLIO, ACCOUNT);
        clock.advance(Duration.ofDays(30));

        assertThat(maintenance.removeOrphans()).isZero();

        assertThat(storage.find(attached.id())).isPresent();
        assertThat(directory(attached)).isDirectory();
    }

    @Test
    void freshOrphanIsKept() {
        var orphan = store(png(320, 240), FilePurpose.REFERENCE);
        clock.advance(Duration.ofHours(23));

        assertThat(maintenance.removeOrphans()).isZero();

        assertThat(storage.find(orphan.id())).isPresent();
        assertThat(directory(orphan)).isDirectory();
    }

    @Test
    void strayDirectoryWithoutRowIsRemoved() throws IOException {
        var stored = store(png(320, 240), FilePurpose.PORTFOLIO);
        setAge(directory(stored), Duration.ofDays(2));
        var stray = createDirectory(
                ROOT.resolve("portfolio").resolve(UUID.randomUUID().toString()));
        setAge(stray, Duration.ofDays(2));
        // Каталог только что перенесённой загрузки, строка которой ещё не зафиксирована, трогать нельзя.
        var fresh = createDirectory(
                ROOT.resolve("reference").resolve(UUID.randomUUID().toString()));
        setAge(fresh, Duration.ofHours(1));
        // Чужое по имени не похоже на файл хранилища и не удаляется.
        var unknown = createDirectory(ROOT.resolve("portfolio").resolve("not-a-file-id"));
        setAge(unknown, Duration.ofDays(2));

        assertThat(maintenance.removeStrayDirectories()).isEqualTo(1);

        assertThat(stray).doesNotExist();
        assertThat(directory(stored)).isDirectory();
        assertThat(fresh).isDirectory();
        assertThat(unknown).isDirectory();
        assertThat(storage.find(stored.id())).isPresent();
    }

    @Test
    void staleUploadLeftoversAreRemoved() throws IOException {
        var temporary = Files.createDirectories(ROOT.resolve(".tmp"));
        var staleUpload = Files.write(temporary.resolve(UUID.randomUUID() + ".upload"), new byte[10]);
        setAge(staleUpload, Duration.ofDays(2));
        var staleStaging = createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        setAge(staleStaging, Duration.ofDays(2));
        var freshUpload = Files.write(temporary.resolve(UUID.randomUUID() + ".upload"), new byte[10]);
        setAge(freshUpload, Duration.ofMinutes(5));

        assertThat(maintenance.removeStrayDirectories()).isEqualTo(2);

        assertThat(staleUpload).doesNotExist();
        assertThat(staleStaging).doesNotExist();
        assertThat(freshUpload).exists();
    }

    @Test
    void unreadableDirectoryDoesNotStopTheSweep() throws IOException {
        var unreadable = Files.createDirectories(ROOT.resolve("portfolio"));
        var stray = createDirectory(
                ROOT.resolve("reference").resolve(UUID.randomUUID().toString()));
        setAge(stray, Duration.ofDays(2));
        Files.setPosixFilePermissions(unreadable, Set.of());
        try {
            assertThat(maintenance.removeStrayDirectories()).isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(stray).doesNotExist();
    }

    /** Каталог с одним вариантом внутри, как у настоящего файла. */
    private static Path createDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve("480.jpg"), new byte[] {1, 2, 3});
        return directory;
    }

    /**
     * Время изменения — по часам приложения, как его сравнивает уборка. Часы тестов стоят на {@code START} (1 октября
     * 2026), а реальные часы позже: каталог, созданный тестом без этой правки, для уборки всегда «свежий». Проверки
     * «свежего» на это и опираются, поэтому возраст им тоже задаётся явно.
     */
    private void setAge(Path path, Duration age) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(clock.instant().minus(age)));
    }
}
