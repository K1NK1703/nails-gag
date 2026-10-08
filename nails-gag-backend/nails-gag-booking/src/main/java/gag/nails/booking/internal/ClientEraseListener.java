package gag.nails.booking.internal;

import gag.nails.booking.CancelReason;
import gag.nails.clients.event.ClientErased;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Обезличивание клиента: его активные записи на ещё не начавшиеся слоты отменяются с причиной
 * {@link CancelReason#ACCOUNT_DELETED} (слот освобождается, признак предоплаты не ставится), а
 * комментарий клиента стирается у всех его записей. Записи, чей слот уже начался, остаются мастеру на
 * отметку: визит либо состоялся, либо нет, и это не зависит от аккаунта. Обычный
 * {@code @EventListener} — выполняется синхронно в транзакции обезличивания, как требует
 * {@link ClientErased}.
 *
 * <p>Сначала берётся блокировка клиента: параллельная самозапись либо уже зафиксирована и попадёт в
 * выборку, либо дождётся конца обезличивания и увидит обезличенную карточку. Затем записи читаются под
 * блокировкой строк: параллельная отмена, перенос или отметка той же записи идут строго до или после.
 *
 * <p>Известное ограничение: обезличивание уже держит строку клиента и только потом берёт записи
 * (клиент → запись), тогда как поздняя отмена, отметки визита и перенос записи клиентом идут в
 * порядке запись → клиент. Если обезличивание совпадёт по времени с такой операцией над записью того
 * же клиента, PostgreSQL обнаружит взаимную блокировку и прервёт одну из транзакций. Принято из-за
 * редкости: удаление аккаунта — ручное действие клиента, прерванную операцию достаточно повторить.
 */
@Component
class ClientEraseListener {

    private final BookingRepository bookings;
    private final ClientLocks locks;
    private final CurrentRules currentRules;
    private final BookingCancellation cancellation;

    ClientEraseListener(
            BookingRepository bookings,
            ClientLocks locks,
            CurrentRules currentRules,
            BookingCancellation cancellation) {
        this.bookings = bookings;
        this.locks = locks;
        this.currentRules = currentRules;
        this.cancellation = cancellation;
    }

    @EventListener
    void on(ClientErased event) {
        locks.acquire(event.clientId());
        var rules = currentRules.get();
        for (var booking : bookings.findAllLockedByClientId(event.clientId())) {
            if (booking.status().isActive() && !rules.visitStarted(booking.slotStartsAt())) {
                cancellation.cancel(booking, CancelReason.ACCOUNT_DELETED, rules.now());
            }
            booking.wipeComment();
        }
    }
}
