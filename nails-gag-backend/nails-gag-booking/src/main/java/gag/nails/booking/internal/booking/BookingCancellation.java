package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.schedule.SlotOccupation;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Отмена записи целиком: состояние, освобождение слота, последствия для клиента, событие. Поздняя
 * отмена клиентом и отказ из-за опоздания ставят клиенту признак обязательной предоплаты; отмена
 * мастером, отмена в окне, истёкший срок предоплаты и удаление аккаунта — нет.
 */
@Component
public class BookingCancellation {

    private final SlotOccupation occupation;
    private final ClientService clients;
    private final BookingEvents events;

    BookingCancellation(SlotOccupation occupation, ClientService clients, BookingEvents events) {
        this.occupation = occupation;
        this.clients = clients;
        this.events = events;
    }

    public BookingView cancel(BookingEntity booking, CancelReason reason, Instant now) {
        booking.cancel(reason, now);
        // Порядок блокировок во всём модуле: запись → клиент → слот.
        switch (reason) {
            case CLIENT_LATE -> requirePrepayment(booking, PrepaymentFlagReason.LATE_CANCELLATION);
            case LATE_ARRIVAL -> requirePrepayment(booking, PrepaymentFlagReason.LATE_ARRIVAL);
            case CLIENT, MASTER, PREPAYMENT_TIMEOUT, ACCOUNT_DELETED -> {}
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
