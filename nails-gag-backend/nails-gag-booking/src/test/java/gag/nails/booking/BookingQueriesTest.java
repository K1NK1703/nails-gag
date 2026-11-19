package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class BookingQueriesTest extends BookingTestSupport {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);

    @Autowired
    VisitMarks marks;

    @Autowired
    EarlierTimeOffers offers;

    @Test
    void clientSeesOwnBookingAndMasterSeesAny() {
        var anna = client("Анна");
        var maria = client("Мария");
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThat(queries.get(booking.id(), Actor.client(anna)).id()).isEqualTo(booking.id());
        assertThat(queries.get(booking.id(), master).id()).isEqualTo(booking.id());
        assertThatThrownBy(() -> queries.get(booking.id(), Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID(), master)).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void activeAndHistoryAreSeparatedAndHistoryIsNewestFirst() {
        var anna = client("Анна");
        var maria = client("Мария");
        var first = bookings.createByMaster(manicure(anna, slot(MONDAY, "12:00")), false, master);
        var second = bookings.createByMaster(manicure(anna, slot(TUESDAY, "12:00")), false, master);
        var third = bookings.createByMaster(manicure(anna, slot(WEDNESDAY, "12:00")), false, master);
        bookings.cancel(first.id(), master);
        bookings.cancel(second.id(), master);

        assertThat(queries.activeOfClient(anna, Actor.client(anna)))
                .extracting(BookingView::id)
                .containsExactly(third.id());
        assertThat(queries.historyOfClient(anna, Actor.client(anna)))
                .extracting(BookingView::id)
                .containsExactly(second.id(), first.id());
        assertThatThrownBy(() -> queries.activeOfClient(anna, Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.historyOfClient(anna, Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void dayAndPeriodListsAreInStartOrderForMasterOnly() {
        var afternoon = book(client("Анна"), slot(MONDAY, "15:00"));
        var noon = book(client("Мария"), slot(MONDAY, "12:00"));
        var tuesday = book(client("Ольга"), slot(TUESDAY, "12:00"));

        assertThat(queries.ofDay(MONDAY, master))
                .extracting(BookingView::id)
                .containsExactly(noon.id(), afternoon.id());
        assertThat(queries.ofPeriod(MONDAY, TUESDAY, master))
                .extracting(BookingView::id)
                .containsExactly(noon.id(), afternoon.id(), tuesday.id());
        assertThatThrownBy(() -> queries.ofDay(MONDAY, Actor.client(noon.clientId())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.ofPeriod(TUESDAY, MONDAY, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "to"));
    }

    @Test
    void dayBoundaryFollowsSalonTimeZone() {
        // 00:30 вторника по Москве — это ещё понедельник по UTC
        var night = book(client("Анна"), slot(TUESDAY, "00:30"));

        assertThat(queries.ofDay(TUESDAY, master)).extracting(BookingView::id).containsExactly(night.id());
        assertThat(queries.ofDay(MONDAY, master)).isEmpty();
    }

    @Test
    void awaitingMarkListsStartedUnmarkedBookings() {
        var slot = slot(MONDAY, "12:00");
        var started = book(client("Анна"), slot);
        book(client("Мария"), slot(TUESDAY, "12:00"));
        assertThat(queries.awaitingMark(master)).isEmpty();

        timeIsAfterStartOf(slot, Duration.ofMinutes(10));
        assertThat(queries.awaitingMark(master)).extracting(BookingView::id).containsExactly(started.id());

        marks.complete(started.id(), master);
        assertThat(queries.awaitingMark(master)).isEmpty();
        assertThatThrownBy(() -> queries.awaitingMark(Actor.client(started.clientId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void awaitingPrepaymentListsBookingsByDeadline() {
        var later = book(clientOwingPrepayment("Анна"), slot(MONDAY, "12:00"));
        var sooner = book(clientOwingPrepayment("Мария"), slot(TODAY, "20:00"));
        book(client("Ольга"), slot(TUESDAY, "12:00"));

        assertThat(queries.awaitingPrepayment(master))
                .extracting(BookingView::id)
                .containsExactly(sooner.id(), later.id());
        assertThatThrownBy(() -> queries.awaitingPrepayment(Actor.client(later.clientId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findIsEmptyForMissingBookingAndChecksAccessLikeGet() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThat(queries.find(booking.id(), master)).contains(queries.get(booking.id(), master));
        assertThat(queries.find(booking.id(), Actor.client(anna))).contains(queries.get(booking.id(), master));
        assertThat(queries.find(UUID.randomUUID(), master)).isEmpty();
        assertThatThrownBy(() -> queries.find(booking.id(), Actor.client(client("Мария"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upcomingListsActiveNotStartedBookingsByVisitStartForMasterOnly() {
        var soonSlot = slot(MONDAY, "10:00");
        var soon = book(client("Анна"), soonSlot);
        var noon = book(client("Мария"), slot(MONDAY, "12:00"));
        var tuesday = book(clientOwingPrepayment("Ольга"), slot(TUESDAY, "12:00"));
        var cancelled = book(client("Вера"), slot(MONDAY, "14:00"));
        bookings.cancel(cancelled.id(), master);
        // Согласованное более раннее время (11:00) ставит запись на 16:00 перед записью на 12:00.
        var earlier = book(client("Нина"), slot(MONDAY, "16:00"));
        var agreedStart = earlier.slotStartsAt().minus(Duration.ofHours(5));
        offers.propose(earlier.id(), agreedStart, master);
        offers.respond(earlier.id(), true, Actor.client(earlier.clientId()));

        assertThat(queries.upcoming(master))
                .extracting(BookingView::id)
                .containsExactly(soon.id(), earlier.id(), noon.id(), tuesday.id());

        timeIsAfterStartOf(soonSlot, Duration.ofMinutes(1));
        assertThat(queries.upcoming(master))
                .extracting(BookingView::id)
                .containsExactly(earlier.id(), noon.id(), tuesday.id());

        // Согласованное время прошло, а слот (16:00) ещё впереди: визит начался.
        clock.set(agreedStart.plus(Duration.ofMinutes(1)));
        assertThat(queries.upcoming(master)).extracting(BookingView::id).containsExactly(noon.id(), tuesday.id());
        assertThatThrownBy(() -> queries.upcoming(Actor.client(noon.clientId())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
