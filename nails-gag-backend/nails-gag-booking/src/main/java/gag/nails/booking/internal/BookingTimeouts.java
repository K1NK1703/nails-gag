package gag.nails.booking.internal;

import gag.nails.booking.CancelReason;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Один шаг фоновой задачи над одной записью — в своей транзакции. Запись перечитывается под
 * блокировкой строки, и условие проверяется заново: между выборкой идентификаторов и шагом её могли
 * подтвердить, отменить или отметить, а предложение — отозвать или получить на него ответ.
 */
@Component
@Transactional
class BookingTimeouts {

    private final BookingRepository bookings;
    private final BookingCancellation cancellation;
    private final Clock clock;

    BookingTimeouts(BookingRepository bookings, BookingCancellation cancellation, Clock clock) {
        this.bookings = bookings;
        this.cancellation = cancellation;
        this.clock = clock;
    }

    boolean cancelOverduePrepayment(UUID bookingId) {
        var now = clock.instant();
        return bookings.findLockedById(bookingId)
                .filter(booking -> booking.isPrepaymentOverdue(now))
                .map(booking -> {
                    cancellation.cancel(booking, CancelReason.PREPAYMENT_TIMEOUT, now);
                    return true;
                })
                .orElse(false);
    }

    boolean expireEarlierTime(UUID bookingId) {
        var now = clock.instant();
        return bookings.findLockedById(bookingId)
                .map(booking -> booking.expireEarlierTime(now))
                .orElse(false);
    }
}
