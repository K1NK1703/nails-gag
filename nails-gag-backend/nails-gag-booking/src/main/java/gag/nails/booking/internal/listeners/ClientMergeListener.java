package gag.nails.booking.internal.listeners;

import gag.nails.booking.internal.booking.BookingRepository;
import gag.nails.booking.internal.booking.ClientLocks;
import gag.nails.clients.event.ClientMerged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Объединение клиентов: записи удалённой карточки переходят к оставшейся, фото-референсы — вместе с
 * ними (они привязаны к записи). Обычный {@code @EventListener} — выполняется синхронно в транзакции
 * объединения, как требует {@link ClientMerged}.
 *
 * <p>Сначала берётся блокировка удаляемого клиента: параллельная запись на эту карточку либо уже
 * зафиксирована и попадёт в выборку, либо дождётся конца объединения и не найдёт клиента. Затем
 * записи читаются под блокировкой строк: параллельная отмена, перенос или отметка той же записи идут
 * строго до или после смены клиента и не затирают друг друга.
 *
 * <p>Известные ограничения:
 *
 * <ul>
 *   <li>Объединение уже держит строки клиентов и только потом берёт записи (клиент → запись), тогда
 *       как поздняя отмена, отметки визита и перенос записи клиентом идут в порядке запись → клиент.
 *       Если объединение совпадёт по времени с такой операцией над записью того же клиента,
 *       PostgreSQL обнаружит взаимную блокировку и прервёт одну из транзакций. Принято из-за
 *       редкости: объединение мастер делает вручную, прерванную операцию достаточно повторить.
 *   <li>Лимит активных записей у оставшегося клиента при объединении не проверяется: активные записи
 *       обеих карточек сохраняются, даже если вместе их больше лимита.
 * </ul>
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
        locks.acquire(event.removedClientId());
        bookings.findAllLockedByClientId(event.removedClientId())
                .forEach(booking -> booking.reassignTo(event.survivorClientId()));
        locks.forget(event.removedClientId());
    }
}
