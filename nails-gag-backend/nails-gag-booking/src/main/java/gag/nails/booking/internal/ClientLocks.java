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

    private static final int ATTEMPTS = 3;

    private final ClientLockRepository locks;

    ClientLocks(ClientLockRepository locks) {
        this.locks = locks;
    }

    /**
     * Строку мог удалить тот, кого мы ждали (объединение клиентов, см. {@link #forget}): тогда она
     * заводится заново. Вызывающий код после блокировки сам проверяет, что клиент ещё существует.
     */
    void acquire(UUID clientId) {
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            locks.ensure(clientId);
            if (locks.lock(clientId).isPresent()) {
                return;
            }
        }
        throw new BookingRowMissingException("booking.client_lock " + clientId);
    }

    /** Клиента больше нет (объединён с другим): его строка не нужна. */
    void forget(UUID clientId) {
        locks.findById(clientId).ifPresent(locks::delete);
    }
}
