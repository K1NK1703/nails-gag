package gag.nails.booking.internal;

import gag.nails.booking.BookingQueries;
import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultBookingQueries implements BookingQueries {

    private static final Set<BookingStatus> FINISHED =
            Set.of(BookingStatus.COMPLETED, BookingStatus.NO_SHOW, BookingStatus.CANCELLED);

    private final BookingRepository bookings;
    private final BookingFinder finder;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultBookingQueries(BookingRepository bookings, BookingFinder finder, SalonTimeZone salonTimeZone, Clock clock) {
        this.bookings = bookings;
        this.finder = finder;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public BookingView get(UUID bookingId, Actor actor) {
        return finder.requireAccessible(bookingId, actor).toView();
    }

    @Override
    public List<BookingView> activeOfClient(UUID clientId, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        return views(
                bookings.findAllByClientIdAndStatusInOrderBySlotStartsAtAscIdAsc(clientId, BookingStatus.active()));
    }

    @Override
    public List<BookingView> historyOfClient(UUID clientId, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        return views(bookings.findAllByClientIdAndStatusInOrderBySlotStartsAtDescIdDesc(clientId, FINISHED));
    }

    @Override
    public List<BookingView> ofDay(LocalDate day, Actor actor) {
        actor.requireMaster();
        return views(bookings.startingBetween(startOf(day), startOf(day.plusDays(1))));
    }

    @Override
    public List<BookingView> ofPeriod(LocalDate from, LocalDate to, Actor actor) {
        actor.requireMaster();
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
        return views(bookings.startingBetween(startOf(from), startOf(to.plusDays(1))));
    }

    @Override
    public List<BookingView> awaitingMark(Actor actor) {
        actor.requireMaster();
        return views(bookings.startedBy(BookingStatus.active(), clock.instant()));
    }

    @Override
    public List<BookingView> awaitingPrepayment(Actor actor) {
        actor.requireMaster();
        return views(bookings.findAllByStatusOrderByPrepaymentDeadlineAscIdAsc(BookingStatus.AWAITING_PREPAYMENT));
    }

    @Override
    public Optional<String> masterNote(UUID bookingId, Actor actor) {
        actor.requireMaster();
        return Optional.ofNullable(finder.require(bookingId).masterNote());
    }

    private Instant startOf(LocalDate day) {
        return salonTimeZone.at(day, LocalTime.MIDNIGHT);
    }

    private static List<BookingView> views(List<BookingEntity> entities) {
        return entities.stream().map(BookingEntity::toView).toList();
    }
}
