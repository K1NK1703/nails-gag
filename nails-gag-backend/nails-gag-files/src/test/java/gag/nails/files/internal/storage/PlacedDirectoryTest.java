package gag.nails.files.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Кто удаляет каталог файла, если строка не сохранилась: сама загрузка или транзакция. */
class PlacedDirectoryTest {

    @TempDir
    Path root;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void failureBeforeHandOverDeletesDirectory() throws IOException {
        var directory = placed();

        new PlacedDirectory(directory).abandon();

        assertThat(directory).doesNotExist();
    }

    @Test
    void afterHandOverTheTransactionOutcomeDecides() throws IOException {
        var directory = placed();
        TransactionSynchronizationManager.initSynchronization();
        var placed = new PlacedDirectory(directory);

        placed.handOverToTransaction();
        // Ошибка фиксации с неизвестным исходом: строка могла сохраниться, каталог не трогается.
        placed.abandon();
        complete(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(directory).isDirectory();

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(directory).doesNotExist();
    }

    @Test
    void committedDirectoryStays() throws IOException {
        var directory = placed();
        TransactionSynchronizationManager.initSynchronization();

        new PlacedDirectory(directory).handOverToTransaction();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(directory).isDirectory();
    }

    private Path placed() throws IOException {
        var directory = Files.createDirectories(root.resolve("portfolio").resolve("file"));
        Files.write(directory.resolve("480.jpg"), new byte[] {1});
        return directory;
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }
}
