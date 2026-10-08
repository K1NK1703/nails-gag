package gag.nails.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class SlotOccupationTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries queries;

    @Autowired
    SlotOccupation occupation;

    private final Actor master = Actor.master();

    @Test
    void occupyMarksSlotWithBooking() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        var bookingId = UUID.randomUUID();

        occupation.occupy(slot.id(), bookingId);

        var occupied = occupation.get(slot.id());
        assertThat(occupied.occupied()).isTrue();
        assertThat(occupied.bookingId()).isEqualTo(bookingId);
        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).isEmpty();
        assertThat(queries.slotsOfDay(FRIDAY, master))
                .extracting(SlotView::bookingId)
                .containsExactly(bookingId);
    }

    @Test
    void slotIsOccupiedOnlyOnce() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        occupation.occupy(slot.id(), UUID.randomUUID());

        assertThatThrownBy(() -> occupation.occupy(slot.id(), UUID.randomUUID()))
                .isInstanceOf(SlotOccupiedException.class);
    }

    @Test
    void occupyingUnknownSlotFails() {
        assertThatThrownBy(() -> occupation.occupy(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(SlotNotFoundException.class);
    }

    @Test
    void releaseFreesSlotForTheBookingThatHoldsIt() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        var bookingId = UUID.randomUUID();
        occupation.occupy(slot.id(), bookingId);

        occupation.release(slot.id(), bookingId);

        var released = occupation.get(slot.id());
        assertThat(released.occupied()).isFalse();
        assertThat(released.bookingId()).isNull();
        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).extracting(SlotView::id).containsExactly(slot.id());
    }

    @Test
    void releaseByAnotherBookingIsAProgrammingError() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        occupation.occupy(slot.id(), UUID.randomUUID());

        assertThatThrownBy(() -> occupation.release(slot.id(), UUID.randomUUID()))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void occupiedSlotCannotBeMovedOrDeleted() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        occupation.occupy(slot.id(), UUID.randomUUID());

        assertThatThrownBy(() -> schedule.moveSlot(slot.id(), LocalTime.of(12, 0), master))
                .isInstanceOf(SlotOccupiedException.class);
        assertThatThrownBy(() -> schedule.deleteSlot(slot.id(), master)).isInstanceOf(SlotOccupiedException.class);
    }

    @Test
    void getUnknownSlotFails() {
        assertThatThrownBy(() -> occupation.get(UUID.randomUUID())).isInstanceOf(SlotNotFoundException.class);
    }

    @Test
    void bookingCannotHoldTwoSlots() {
        var first = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        var second = schedule.addSlot(FRIDAY, LocalTime.of(12, 0), master);
        var bookingId = UUID.randomUUID();
        occupation.occupy(first.id(), bookingId);

        assertThatThrownBy(() -> occupation.occupy(second.id(), bookingId)).isInstanceOf(InternalException.class);
    }

    @Test
    void releasedSlotCanBeOccupiedAgain() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        occupation.occupy(slot.id(), first);
        occupation.release(slot.id(), first);

        occupation.occupy(slot.id(), second);

        assertThat(occupation.get(slot.id()).bookingId()).isEqualTo(second);
    }
}
