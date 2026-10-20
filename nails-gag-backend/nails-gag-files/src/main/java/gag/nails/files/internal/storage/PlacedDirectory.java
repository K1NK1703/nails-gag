package gag.nails.files.internal.storage;

import java.nio.file.Path;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Каталог файла, уже перенесённый на место, пока строка в базе не сохранена. Удалить его при неудаче должен ровно один
 * участник: до {@link #handOverToTransaction()} — сама загрузка ({@link #abandon()}), после — транзакция. Транзакция
 * удаляет каталог только при откате: при неизвестном исходе фиксации строка могла сохраниться, и удалить файлы значило
 * бы оставить строку без них; лишний каталог уберёт сверка диска с базой.
 */
final class PlacedDirectory {

    private final Path directory;
    private boolean handedOver;

    PlacedDirectory(Path directory) {
        this.directory = directory;
    }

    /** Отдаёт удаление каталога текущей транзакции; вызывать до вставки строки. */
    void handOverToTransaction() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    StorageLayout.deleteQuietly(directory);
                }
            }
        });
        handedOver = true;
    }

    /** Загрузка не удалась: удаляет каталог, если судьбу его ещё не решает транзакция. */
    void abandon() {
        if (!handedOver) {
            StorageLayout.deleteQuietly(directory);
        }
    }
}
