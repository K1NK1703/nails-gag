package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.ClientErasedException;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingEraseTest extends BookingTestSupport {

    @Autowired
    VisitMarks marks;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void futureBookingsAreCancelledAndSlotsFreed(PublishedEvents events) {
        var anna = client("Анна");
        var monday = slot(MONDAY, "12:00");
        var tuesday = slot(TUESDAY, "12:00");
        var first = book(anna, monday);
        var second = bookings.createByMaster(manicure(anna, tuesday), false, master);

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        for (var id : List.of(first.id(), second.id())) {
            var cancelled = queries.get(id, master);
            assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
            assertThat(cancelled.cancelledAt()).isEqualTo(TestTimeConfiguration.START);
        }
        assertThat(scheduleQueries.freeSlots(MONDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactlyInAnyOrder(monday.id(), tuesday.id());
        assertThat(queries.activeOfClient(anna, master)).isEmpty();
        assertThat(queries.historyOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(events.ofType(BookingCancelled.class)
                        .matching(e -> e.clientId().equals(anna) && e.reason() == CancelReason.ACCOUNT_DELETED))
                .hasSize(2);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master)).isEmpty();
    }

    @Test
    void bookingAwaitingPrepaymentIsCancelledToo() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(manicure(anna, slot), true, master);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        var cancelled = queries.get(booking.id(), master);
        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
        assertThat(cancelled.prepaymentDeadline()).isNull();
        assertThat(queries.awaitingPrepayment(master)).isEmpty();
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
    }

    @Test
    void startedBookingIsLeftForTheMasterToMark() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(queries.awaitingMark(master)).extracting(BookingView::id).containsExactly(booking.id());
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();

        var noShow = marks.markNoShow(booking.id(), master);

        assertThat(noShow.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(owesPrepayment(anna)).isTrue();
    }

    @Test
    void clientCommentsAreWiped() {
        var anna = client("Анна");
        var past = slot(MONDAY, "12:00");
        var future = slot(TUESDAY, "12:00");
        var completed = bookings.createByClient(
                new CreateBooking(anna, past.id(), MANICURE, List.of(), "Короткие, квадрат"), Actor.client(anna));
        bookings.updateMasterNote(completed.id(), "Принесёт свой лак", master);
        timeIsAfterStartOf(past, Duration.ofHours(2));
        marks.complete(completed.id(), master);
        var cancelled = bookings.createByMaster(
                new CreateBooking(anna, future.id(), MANICURE, List.of(), "Хочу френч"), false, master);

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(completed.id(), master).comment()).isNull();
        assertThat(queries.get(cancelled.id(), master).comment()).isNull();
        assertThat(queries.get(cancelled.id(), master).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(queries.masterNote(completed.id(), master)).contains("Принесёт свой лак");
    }

    @Test
    void pastVisitsKeepTheirAmounts() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        var kept = queries.get(booking.id(), master);
        assertThat(kept.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(kept.clientId()).isEqualTo(anna);
        assertThat(kept.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);
        assertThat(kept.finalAmount()).isEqualByComparingTo("2800.00");
        assertThat(queries.historyOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();
    }

    @Test
    void masterCannotBookErasedClient() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        clients.erase(anna);

        assertThatThrownBy(() -> bookings.createByMaster(manicure(anna, slot), false, master))
                .isInstanceOf(ClientErasedException.class)
                .satisfies(e -> assertThat(((ClientErasedException) e).params()).containsEntry("clientId", anna));
        assertThatThrownBy(() -> book(anna, slot)).isInstanceOf(ClientErasedException.class);

        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
    }
}
