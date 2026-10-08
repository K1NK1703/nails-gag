package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.clients.model.ChangedBy;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingCancellationTest extends BookingTestSupport {

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void clientCancelsInFreeWindowWithoutConsequences() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);

        var cancelled = bookings.cancel(booking.id(), Actor.client(anna));

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(cancelled.cancelledAt()).isEqualTo(TestTimeConfiguration.START);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
        assertThat(queries.activeOfClient(anna, master)).isEmpty();
    }

    @Test
    void lateCancellationByClientRequiresPrepaymentNextTime() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsBefore(slot, Duration.ofHours(23));

        var cancelled = bookings.cancel(booking.id(), Actor.client(anna));

        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT_LATE);
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master)).first().satisfies(change -> {
            assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.LATE_CANCELLATION);
            assertThat(change.setBy()).isEqualTo(ChangedBy.SYSTEM);
            assertThat(change.bookingId()).isEqualTo(booking.id());
        });
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).hasSize(1);
    }

    @Test
    void cancellationExactlyAtTheWindowBoundaryIsFree() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsBefore(slot, Duration.ofHours(24));

        var cancelled = bookings.cancel(booking.id(), Actor.client(anna));

        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(owesPrepayment(anna)).isFalse();
    }

    @Test
    void masterCancelsAtAnyTimeWithoutConsequencesForClient() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsBefore(slot, Duration.ofHours(1));

        var cancelled = bookings.cancel(booking.id(), master);

        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.MASTER);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(scheduleQueries.slotsOfDay(MONDAY, master)).singleElement().matches(free -> !free.occupied());
    }

    @Test
    void cancelledBookingCannotBeCancelledAgain() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        bookings.cancel(booking.id(), master);

        assertThatThrownBy(() -> bookings.cancel(booking.id(), master))
                .isInstanceOf(InvalidTransitionException.class)
                .satisfies(e -> assertThat(((InvalidTransitionException) e).params())
                        .containsEntry("status", "CANCELLED")
                        .containsEntry("action", "cancel"));
    }

    @Test
    void clientCannotCancelSomeoneElsesBooking() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));
        var maria = client("Мария");

        assertThatThrownBy(() -> bookings.cancel(booking.id(), Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownBookingIsNotFound() {
        assertThatThrownBy(() -> bookings.cancel(UUID.randomUUID(), master))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void masterRefusesClientWhoIsLate() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(20));

        var refused = bookings.refuseLateArrival(booking.id(), master);

        assertThat(refused.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(refused.cancelReason()).isEqualTo(CancelReason.LATE_ARRIVAL);
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .first()
                .satisfies(change -> assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.LATE_ARRIVAL));
        assertThat(scheduleQueries.slotsOfDay(MONDAY, master)).singleElement().matches(free -> !free.occupied());
    }

    @Test
    void lateArrivalCannotBeRefusedBeforeSlotStartOrByClient() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> bookings.refuseLateArrival(booking.id(), master))
                .isInstanceOf(VisitNotStartedException.class);
        assertThatThrownBy(() -> bookings.refuseLateArrival(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cancellationPublishesEvent(PublishedEvents events) {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        bookings.cancel(booking.id(), Actor.client(anna));

        assertThat(events.ofType(BookingCancelled.class)
                        .matching(e -> e.bookingId().equals(booking.id()) && e.reason() == CancelReason.CLIENT))
                .hasSize(1);
    }

    @Test
    void clientEditsCommentWhileBookingIsActive() {
        var anna = client("Анна");
        var actor = Actor.client(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThat(bookings.updateComment(booking.id(), "Приду с дочкой", actor).comment())
                .isEqualTo("Приду с дочкой");
        assertThat(bookings.updateComment(booking.id(), "  ", actor).comment()).isNull();

        bookings.cancel(booking.id(), actor);
        assertThatThrownBy(() -> bookings.updateComment(booking.id(), "Передумала", actor))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void commentLongerThanLimitIsRejected() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> bookings.updateComment(booking.id(), "а".repeat(501), Actor.client(anna)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "comment"));
    }

    @Test
    void masterNoteIsVisibleOnlyToMaster() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        bookings.updateMasterNote(booking.id(), "Принесёт свой лак", master);

        assertThat(queries.masterNote(booking.id(), master)).contains("Принесёт свой лак");
        assertThatThrownBy(() -> queries.masterNote(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bookings.updateMasterNote(booking.id(), "Моя заметка", Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        bookings.updateMasterNote(booking.id(), " ", master);
        assertThat(queries.masterNote(booking.id(), master)).isEmpty();
    }

    @Test
    void masterNoteOfMaximumLengthIsStored() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));
        var note = "я".repeat(2000);

        bookings.updateMasterNote(booking.id(), note, master);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.masterNote(booking.id(), master)).contains(note);
    }

    @Test
    void masterNoteLongerThanMaximumIsRejected() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> bookings.updateMasterNote(booking.id(), "я".repeat(2001), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "note"));
        assertThat(queries.masterNote(booking.id(), master)).isEmpty();
    }

    @Test
    void clientCannotCancelAfterSlotStart() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(1));

        assertThatThrownBy(() -> bookings.cancel(booking.id(), Actor.client(anna)))
                .isInstanceOf(InvalidTransitionException.class)
                .satisfies(e -> assertThat(((InvalidTransitionException) e).params())
                        .containsEntry("status", "CONFIRMED")
                        .containsEntry("action", "cancel a started visit"));
        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(scheduleQueries.slotsOfDay(MONDAY, master))
                .singleElement()
                .matches(taken -> taken.occupied() && booking.id().equals(taken.bookingId()));
    }

    @Test
    void cancelledBookingAwaitingPrepaymentIsStoredWithoutDeadlineAndFreesSlot() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(booking.prepaymentDeadline()).isNotNull();

        bookings.cancel(booking.id(), Actor.client(anna));
        entityManager.flush();
        entityManager.clear();

        var stored = queries.get(booking.id(), master);
        assertThat(stored.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(stored.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(stored.prepaymentDeadline()).isNull();
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
    }

    @Test
    void lateCancellationPublishesEventWithLateReason(PublishedEvents events) {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsBefore(slot, Duration.ofHours(23));

        bookings.cancel(booking.id(), Actor.client(anna));

        assertThat(events.ofType(BookingCancelled.class)
                        .matching(e -> e.bookingId().equals(booking.id()) && e.reason() == CancelReason.CLIENT_LATE))
                .hasSize(1);
    }

    @Test
    void lateArrivalIsRefusedExactlyAtSlotStartButNotTwice() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ZERO);

        var refused = bookings.refuseLateArrival(booking.id(), master);

        assertThat(refused.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(refused.cancelReason()).isEqualTo(CancelReason.LATE_ARRIVAL);
        assertThat(refused.cancelledAt()).isEqualTo(slot.startsAt());
        assertThatThrownBy(() -> bookings.refuseLateArrival(booking.id(), master))
                .isInstanceOf(InvalidTransitionException.class)
                .satisfies(e ->
                        assertThat(((InvalidTransitionException) e).params()).containsEntry("status", "CANCELLED"));
    }

    @Test
    void commentLengthIsCountedInCharactersNotUtf16Units() {
        var anna = client("Анна");
        var actor = Actor.client(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var emoji = "💅".repeat(500);

        bookings.updateComment(booking.id(), emoji, actor);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(booking.id(), master).comment()).isEqualTo(emoji);
        assertThatThrownBy(() -> bookings.updateComment(booking.id(), "💅".repeat(501), actor))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "comment"));
    }
}
