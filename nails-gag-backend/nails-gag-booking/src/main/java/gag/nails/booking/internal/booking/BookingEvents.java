package gag.nails.booking.internal.booking;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.event.BookingCompleted;
import gag.nails.booking.event.BookingConfirmed;
import gag.nails.booking.event.BookingCreated;
import gag.nails.booking.event.BookingNoShow;
import gag.nails.booking.event.BookingRescheduled;
import gag.nails.booking.event.EarlierTimeAccepted;
import gag.nails.booking.event.EarlierTimeDeclined;
import gag.nails.booking.event.EarlierTimeProposed;
import gag.nails.booking.event.PrepaymentRequested;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * События записей. Публикуются внутри транзакции операции (спецификация 7.8); каждый метод
 * вызывается после того, как запись уже переведена в новое состояние.
 */
@Component
public class BookingEvents {

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

    public void confirmed(BookingEntity b, Instant now) {
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

    public void completed(BookingEntity b, Instant now) {
        publisher.publishEvent(new BookingCompleted(b.id(), b.clientId(), b.slotStartsAt(), now));
    }

    public void noShow(BookingEntity b, Instant now) {
        publisher.publishEvent(new BookingNoShow(b.id(), b.clientId(), b.slotStartsAt(), now));
    }

    public void earlierTimeProposed(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeProposed(b.id(), b.clientId(), b.slotStartsAt(), b.proposedStart(), now));
    }

    public void earlierTimeAccepted(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeAccepted(b.id(), b.clientId(), b.slotStartsAt(), b.agreedStart(), now));
    }

    public void earlierTimeDeclined(BookingEntity b, Instant now) {
        publisher.publishEvent(new EarlierTimeDeclined(b.id(), b.clientId(), b.slotStartsAt(), now));
    }
}
