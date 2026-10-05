package gag.nails.booking.internal;

import gag.nails.booking.BookingView;
import gag.nails.booking.CancelReason;
import gag.nails.clients.ClientService;
import gag.nails.clients.PrepaymentFlagChange;
import gag.nails.clients.PrepaymentFlagReason;
import gag.nails.schedule.SlotOccupation;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Отмена записи целиком: состояние, освобождение слота, последствия для клиента, событие. Поздняя
 * отмена клиентом и отказ из-за опоздания ставят клиенту признак обязательной предоплаты; отмена
 * мастером, отмена в окне и истёкший срок предоплаты — нет.
 */
@Component
class BookingCancellation {

    private final SlotOccupation occupation;
    private final ClientService clients;
    private final BookingEvents events;

    BookingCancellation(SlotOccupation occupation, ClientService clients, BookingEvents events) {
        this.occupation = occupation;
        this.clients = clients;
        this.events = events;
    }

    BookingView cancel(BookingEntity booking, CancelReason reason, Instant now) {
        booking.cancel(reason, now);
        // Порядок блокировок во всём модуле: запись → клиент → слот.
        switch (reason) {
            case CLIENT_LATE -> requirePrepayment(booking, PrepaymentFlagReason.LATE_CANCELLATION);
            case LATE_ARRIVAL -> requirePrepayment(booking, PrepaymentFlagReason.LATE_ARRIVAL);
            case CLIENT, MASTER, PREPAYMENT_TIMEOUT -> {}
        }
        occupation.release(booking.slotId(), booking.id());
        events.cancelled(booking, now);
        return booking.toView();
    }

    private void requirePrepayment(BookingEntity booking, PrepaymentFlagReason reason) {
        clients.applySystemPrepaymentChange(
                booking.clientId(), new PrepaymentFlagChange(true, reason, booking.id(), null));
    }
}
