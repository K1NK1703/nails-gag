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
import gag.nails.common.time.ReportPeriod;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock clock;

    DefaultBookingFinance(BookingRepository bookings, SalonTimeZone salonTimeZone, Clock clock) {
        this.bookings = bookings;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public List<VisitFact> visits(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var period = period(from, to);
        return bookings
                .completedVisitsWithServices(
                        BookingStatus.COMPLETED, period.startIn(salonTimeZone), period.endIn(salonTimeZone))
                .stream()
                .map(booking -> booking.toVisitFact(salonTimeZone.dayOf(booking.visitStart())))
                .toList();
    }

    @Override
    public List<KeptPrepaymentFact> keptPrepayments(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var period = period(from, to);
        return bookings
                .settledWithReceivedPrepayment(SETTLED, period.startIn(salonTimeZone), period.endIn(salonTimeZone))
                .stream()
                .filter(booking -> booking.prepaymentOutcome() == PrepaymentOutcome.KEPT)
                .map(booking -> booking.toKeptPrepaymentFact(salonTimeZone.dayOf(booking.settledAt())))
                .toList();
    }

    private ReportPeriod period(LocalDate from, LocalDate to) {
        return ReportPeriod.of(from, to, salonTimeZone.today(clock));
    }
}
