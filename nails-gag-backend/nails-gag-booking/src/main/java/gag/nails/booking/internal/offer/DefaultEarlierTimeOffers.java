package gag.nails.booking.internal.offer;

import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.internal.booking.BookingEvents;
import gag.nails.booking.internal.booking.BookingFinder;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultEarlierTimeOffers implements EarlierTimeOffers {

    private final BookingFinder finder;
    private final BookingEvents events;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultEarlierTimeOffers(BookingFinder finder, BookingEvents events, SalonTimeZone salonTimeZone, Clock clock) {
        this.finder = finder;
        this.events = events;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public BookingView propose(UUID bookingId, Instant proposedStart, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        booking.requireClientNotErased("propose earlier time");
        var now = clock.instant();
        var slotStartsAt = booking.slotStartsAt();
        if (!salonTimeZone.dayOf(proposedStart).equals(salonTimeZone.dayOf(slotStartsAt))) {
            throw new ValidationException("proposedStart", "must be on the day of the slot");
        }
        if (!proposedStart.isAfter(now) || !proposedStart.isBefore(slotStartsAt)) {
            throw new ValidationException("proposedStart", "must be between now and the slot start");
        }
        booking.proposeEarlierTime(proposedStart, now);
        events.earlierTimeProposed(booking, now);
        return booking.toView(actor);
    }

    @Override
    public BookingView withdraw(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        booking.withdrawEarlierTime();
        return booking.toView(actor);
    }

    @Override
    public BookingView respond(UUID bookingId, boolean accept, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        var now = clock.instant();
        booking.answerEarlierTime(accept, now);
        if (accept) {
            events.earlierTimeAccepted(booking, now);
        } else {
            events.earlierTimeDeclined(booking, now);
        }
        return booking.toView(actor);
    }
}
