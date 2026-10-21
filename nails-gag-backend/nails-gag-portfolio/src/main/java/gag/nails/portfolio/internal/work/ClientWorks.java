package gag.nails.portfolio.internal.work;

import gag.nails.files.FileStorage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Работы клиента для подписчиков событий его карточки. Сначала блокировка клиента ({@link PortfolioLocks#client}):
 * добавление его работы, начатое раньше, уже зафиксировано и попадёт в выборку, начатое позже — дождётся и увидит
 * итог. Затем строки работ под блокировкой: параллельная правка, показ, перестановка или удаление той же работы идут
 * строго до или после.
 */
@Component
public class ClientWorks {

    private final WorkRepository works;
    private final PortfolioLocks locks;
    private final FileStorage files;

    ClientWorks(WorkRepository works, PortfolioLocks locks, FileStorage files) {
        this.works = works;
        this.locks = locks;
        this.files = files;
    }

    /** Работы удалённой при объединении карточки переходят к оставшейся. */
    public void reassign(UUID removedClientId, UUID survivorClientId) {
        locks.client(removedClientId);
        works.findAllLockedByClientId(removedClientId).forEach(work -> work.reassignTo(survivorClientId));
    }

    /** Удаляет работы клиента и их файлы (файлы на диске — после фиксации). */
    public void deleteAll(UUID clientId) {
        locks.client(clientId);
        for (var work : works.findAllLockedByClientId(clientId)) {
            works.delete(work);
            files.delete(work.fileId());
        }
    }
}
