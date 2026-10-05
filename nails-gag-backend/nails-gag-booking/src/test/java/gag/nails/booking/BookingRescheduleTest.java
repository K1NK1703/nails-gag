package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LateRescheduleNotAllowedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingRescheduleTest extends BookingTestSupport {

    @Test
    void clientReschedulesWithinFreeWindow() {
        var anna = client("Анна");
        var from = slot(MONDAY, "12:00");
        var to = slot(TUESDAY, "15:00");
        var booking = book(anna, from);

        var moved = bookings.reschedule(booking.id(), to.id(), Actor.client(anna));

        assertThat(moved.id()).isEqualTo(booking.id());
        assertThat(moved.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(moved.slotId()).isEqualTo(to.id());
        assertThat(moved.slotStartsAt()).isEqualTo(to.startsAt());
        assertThat(moved.slotEndsAt()).isEqualTo(to.endsAt());
        assertThat(scheduleQueries.freeSlots(MONDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactly(from.id());
        assertThat(scheduleQueries.slotsOfDay(TUESDAY, master))
                .extracting(SlotView::bookingId)
                .containsExactly(booking.id());
    }

    @Test
    void lateRescheduleByClientIsNotAllowed() {
        var anna = client("Анна");
        var from = slot(MONDAY, "12:00");
        var to = slot(TUESDAY, "15:00");
        var booking = book(anna, from);
        timeIsBefore(from, Duration.ofHours(23));

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), to.id(), Actor.client(anna)))
                .isInstanceOf(LateRescheduleNotAllowedException.class)
                .satisfies(e -> assertThat(((LateRescheduleNotAllowedException) e).params())
                        .containsEntry("windowHours", 24));
    }

    @Test
    void clientCannotRescheduleIntoSlotCloserThanCutoff() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        var soon = slot(TODAY, "10:30");

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), soon.id(), Actor.client(anna)))
                .isInstanceOf(TooLateToBookException.class);
    }

    @Test
    void masterReschedulesAtAnyTime() {
        var anna = client("Анна");
        var from = slot(MONDAY, "12:00");
        var to = slot(MONDAY, "16:00");
        var booking = book(anna, from);
        timeIsBefore(from, Duration.ofHours(1));

        var moved = bookings.reschedule(booking.id(), to.id(), master);

        assertThat(moved.slotId()).isEqualTo(to.id());
        assertThat(owesPrepayment(anna)).isFalse();
    }

    @Test
    void rescheduleIntoOccupiedSlotFails() {
        var anna = client("Анна");
        var taken = slot(TUESDAY, "15:00");
        book(client("Мария"), taken);
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), taken.id(), Actor.client(anna)))
                .isInstanceOf(SlotOccupiedException.class);
    }

    @Test
    void rescheduleKeepsPrepaymentWaitingAndCapsItsDeadline() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        var tonight = slot(TODAY, "20:00");

        var moved = bookings.reschedule(booking.id(), tonight.id(), Actor.client(anna));

        assertThat(moved.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        // прежний срок — через сутки; новый слот сегодня в 17:00 UTC, срок — за два часа до него
        assertThat(moved.prepaymentDeadline()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
    }

    @Test
    void rescheduleToTheSameSlotIsRejected() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), slot.id(), Actor.client(anna)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "newSlotId"));
    }

    @Test
    void cancelledBookingCannotBeRescheduled() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        var to = slot(TUESDAY, "15:00");
        bookings.cancel(booking.id(), master);

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), to.id(), master))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void clientCannotRescheduleSomeoneElsesBooking() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));
        var to = slot(TUESDAY, "15:00");
        var maria = client("Мария");

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), to.id(), Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reschedulePublishesEvent(PublishedEvents events) {
        var anna = client("Анна");
        var from = slot(MONDAY, "12:00");
        var to = slot(TUESDAY, "15:00");
        var booking = book(anna, from);

        bookings.reschedule(booking.id(), to.id(), Actor.client(anna));

        assertThat(events.ofType(BookingRescheduled.class)
                        .matching(e -> e.bookingId().equals(booking.id())
                                && e.oldSlotId().equals(from.id())
                                && e.oldSlotStartsAt().equals(from.startsAt())
                                && e.newSlotId().equals(to.id())
                                && e.slotStartsAt().equals(to.startsAt())))
                .hasSize(1);
    }
}
