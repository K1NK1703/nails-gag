package gag.nails.booking.internal;

import gag.nails.clients.ClientMerged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Объединение клиентов: записи удалённой карточки переходят к оставшейся. Обычный
 * {@code @EventListener} — выполняется синхронно в транзакции объединения, как требует
 * {@link ClientMerged}. Записи правятся через сущности, а не массовым UPDATE: так загруженные раньше
 * копии не остаются со старым клиентом.
 */
@Component
class ClientMergeListener {

    private final BookingRepository bookings;
    private final ClientLocks locks;

    ClientMergeListener(BookingRepository bookings, ClientLocks locks) {
        this.bookings = bookings;
        this.locks = locks;
    }

    @EventListener
    void on(ClientMerged event) {
        bookings.findAllByClientId(event.removedClientId())
                .forEach(booking -> booking.reassignTo(event.survivorClientId()));
        locks.forget(event.removedClientId());
    }
}
