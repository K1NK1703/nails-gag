package gag.nails.booking.internal.finance;

import gag.nails.booking.BookingFinance;
import gag.nails.booking.internal.booking.BookingEntity;
import gag.nails.booking.internal.booking.BookingRepository;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.KeptPrepaymentFact;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.booking.model.VisitFact;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Факты читаются одним запросом на метод: визиты — вместе с составом (выборка с присоединением состава), предоплаты —
 * без него. Для предоплат запрос только отбирает кандидатов — отменённые записи и неявки с полученной предоплатой в
 * периоде; удержана ли предоплата, решает правило записи ({@code PrepaymentOutcomes} через
 * {@link BookingEntity#prepaymentOutcome}), чтобы правило не повторялось в SQL.
 */
@Service
@Transactional(readOnly = true)
class DefaultBookingFinance implements BookingFinance {

    private static final Set<BookingStatus> SETTLED = Set.of(BookingStatus.CANCELLED, BookingStatus.NO_SHOW);

    private final BookingRepository bookings;
    private final SalonTimeZone salonTimeZone;

    DefaultBookingFinance(BookingRepository bookings, SalonTimeZone salonTimeZone) {
        this.bookings = bookings;
        this.salonTimeZone = salonTimeZone;
    }

    @Override
    public List<VisitFact> visits(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        requireValidPeriod(from, to);
        return bookings
                .completedVisitsWithServices(BookingStatus.COMPLETED, startOf(from), startOf(to.plusDays(1)))
                .stream()
                .map(booking -> booking.toVisitFact(salonTimeZone.dayOf(booking.visitStart())))
                .toList();
    }

    @Override
    public List<KeptPrepaymentFact> keptPrepayments(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        requireValidPeriod(from, to);
        return bookings.settledWithReceivedPrepayment(SETTLED, startOf(from), startOf(to.plusDays(1))).stream()
                .filter(booking -> booking.prepaymentOutcome() == PrepaymentOutcome.KEPT)
                .map(booking -> booking.toKeptPrepaymentFact(salonTimeZone.dayOf(booking.requirePrepaymentSettled())))
                .toList();
    }

    private static void requireValidPeriod(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new ValidationException("to", "period must be at most " + MAX_PERIOD_DAYS + " days");
        }
    }

    private Instant startOf(LocalDate day) {
        return salonTimeZone.at(day, LocalTime.MIDNIGHT);
    }
}
