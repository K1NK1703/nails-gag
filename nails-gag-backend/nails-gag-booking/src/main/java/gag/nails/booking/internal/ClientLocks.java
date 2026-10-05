package gag.nails.booking.internal;

import gag.nails.booking.internal.error.BookingRowMissingException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Блокировка «на клиента» до конца транзакции: две параллельные записи одного клиента проходят
 * проверку лимита по очереди (спецификация 7.6). Уникальный индекс здесь не годится: он работал бы
 * только при лимите 1.
 */
@Component
class ClientLocks {

    private final ClientLockRepository locks;

    ClientLocks(ClientLockRepository locks) {
        this.locks = locks;
    }

    void acquire(UUID clientId) {
        locks.ensure(clientId);
        locks.lock(clientId).orElseThrow(() -> new BookingRowMissingException("booking.client_lock " + clientId));
    }

    /** Клиента больше нет (объединён с другим): его строка не нужна. */
    void forget(UUID clientId) {
        locks.findById(clientId).ifPresent(locks::delete);
    }
}
