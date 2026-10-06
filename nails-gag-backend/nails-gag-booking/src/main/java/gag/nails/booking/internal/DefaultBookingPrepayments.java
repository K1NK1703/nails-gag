package gag.nails.booking.internal;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
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
    private final ClientQueries clientQueries;
    private final Clock clock;

    DefaultBookingPrepayments(
            BookingFinder finder,
            BookingEvents events,
            ClientService clients,
            ClientQueries clientQueries,
            Clock clock) {
        this.finder = finder;
        this.events = events;
        this.clients = clients;
        this.clientQueries = clientQueries;
        this.clock = clock;
    }

    @Override
    public BookingView markReceived(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        var now = clock.instant();
        booking.confirm(now, true);
        events.confirmed(booking, now);
        return booking.toView();
    }

    @Override
    public BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        // Признак клиента снимается от имени сотрудника — нужен и уровень изменения «Клиентов». Оба
        // уровня проверяются до изменений, чтобы отказ не оставил запись подтверждённой.
        actor.requireWrite(Section.CLIENTS);
        var booking = finder.requireForUpdate(bookingId);
        var now = clock.instant();
        booking.confirm(now, false);
        // Сотрудник решил не требовать предоплату: признак снимается с причиной MASTER_WAIVED. Если его
        // и не было (предоплату запросили при записи), журнал признака не трогаем.
        if (owesPrepayment(booking.clientId())) {
            clients.setPrepaymentRequired(booking.clientId(), false, null, actor);
        }
        events.confirmed(booking, now);
        return booking.toView();
    }

    private boolean owesPrepayment(UUID clientId) {
        return clientQueries.byId(clientId).map(ClientView::prepaymentRequired).orElse(false);
    }
}
