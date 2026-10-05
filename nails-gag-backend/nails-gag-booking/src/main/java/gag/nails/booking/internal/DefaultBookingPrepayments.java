package gag.nails.booking.internal;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingView;
import gag.nails.clients.ClientService;
import gag.nails.common.actor.Actor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultBookingPrepayments implements BookingPrepayments {

    private final BookingFinder finder;
    private final BookingEvents events;
    private final ClientService clients;
    private final Clock clock;

    DefaultBookingPrepayments(BookingFinder finder, BookingEvents events, ClientService clients, Clock clock) {
        this.finder = finder;
        this.events = events;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    public BookingView markReceived(UUID bookingId, Actor actor) {
        actor.requireMaster();
        var booking = finder.require(bookingId);
        var now = clock.instant();
        booking.confirm(now, true);
        events.confirmed(booking, now);
        return booking.toView();
    }

    @Override
    public BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor) {
        actor.requireMaster();
        var booking = finder.require(bookingId);
        var now = clock.instant();
        booking.confirm(now, false);
        // Мастер решила не требовать предоплату: признак снимается с причиной MASTER_WAIVED.
        clients.setPrepaymentRequired(booking.clientId(), false, null, actor);
        events.confirmed(booking, now);
        return booking.toView();
    }
}
