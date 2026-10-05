package gag.nails.booking.internal;

import gag.nails.booking.BookingCancelled;
import gag.nails.booking.BookingCompleted;
import gag.nails.booking.BookingConfirmed;
import gag.nails.booking.BookingCreated;
import gag.nails.booking.BookingNoShow;
import gag.nails.booking.BookingRescheduled;
import gag.nails.booking.EarlierTimeAccepted;
import gag.nails.booking.EarlierTimeDeclined;
import gag.nails.booking.EarlierTimeProposed;
import gag.nails.booking.PrepaymentRequested;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * События записей. Публикуются внутри транзакции операции (спецификация 7.8); каждый метод
 * вызывается после того, как запись уже переведена в новое состояние.
 */
@Component
class BookingEvents {

    private final ApplicationEventPublisher publisher;

    BookingEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Запись создана; если она ждёт предоплату — ещё и запрос предоплаты. */
    void created(BookingEntity b, Instant now) {
        publisher.publishEvent(
                new BookingCreated(b.id(), b.clientId(), b.slotStartsAt(), b.status(), b.createdBy(), now));
        var deadline = b.prepaymentDeadline();
        if (deadline != null) {
            publisher.publishEvent(new PrepaymentRequested(b.id(), b.clientId(), b.slotStartsAt(), deadline, now));
        }
    }

    void confirmed(BookingEntity b, Instant now) {
        publisher.publishEvent(
                new BookingConfirmed(b.id(), b.clientId(), b.slotStartsAt(), b.prepaymentReceivedAt() != null, now));
    }

    void cancelled(BookingEntity b, Instant now) {
        publisher.publishEvent(new BookingCancelled(b.id(), b.clientId(), b.slotStartsAt(), b.cancelReason(), now));
    }

    void rescheduled(BookingEntity b, UUID oldSlotId, Instant oldSlotStartsAt, Instant now) {
        publisher.publishEvent(new BookingRescheduled(
                b.id(), b.clientId(), oldSlotId, oldSlotStartsAt, b.slotId(), b.slotStartsAt(), now));
    }

    void completed(BookingEntity b, Instant now) {
        publisher.publishEvent(new BookingCompleted(b.id(), b.clientId(), b.slotStartsAt(), now));
    }

    void noShow(BookingEntity b, Instant now) {
        publisher.publishEvent(new BookingNoShow(b.id(), b.clientId(), b.slotStartsAt(), now));
    }

    void earlierTimeProposed(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeProposed(b.id(), b.clientId(), b.slotStartsAt(), b.proposedStart(), now));
    }

    void earlierTimeAccepted(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeAccepted(b.id(), b.clientId(), b.slotStartsAt(), b.agreedStart(), now));
    }

    void earlierTimeDeclined(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeDeclined(b.id(), b.clientId(), b.slotStartsAt(), now));
    }
}
