package gag.nails.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOverlapException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class SlotsTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries queries;

    @Autowired
    ScheduleSettingsService settings;

    private final Actor master = Actor.master();

    @Test
    void masterOpensSlotWithDurationFromSettings() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);

        assertThat(slot.day()).isEqualTo(FRIDAY);
        assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-10-02T06:30:00Z"));
        assertThat(slot.endsAt()).isEqualTo(Instant.parse("2026-10-02T08:30:00Z"));
        assertThat(slot.durationMinutes()).isEqualTo(120);
        assertThat(slot.occupied()).isFalse();
        assertThat(slot.bookingId()).isNull();
        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).extracting(SlotView::id).containsExactly(slot.id());
    }

    @Test
    void overlappingSlotIsRejectedByTheDatabase() {
        schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);

        assertThatThrownBy(() -> schedule.addSlot(FRIDAY, LocalTime.of(10, 30), master))
                .isInstanceOf(SlotOverlapException.class)
                .satisfies(e -> assertThat(((SlotOverlapException) e).params())
                        .containsEntry("startsAt", Instant.parse("2026-10-02T07:30:00Z")));
    }

    @Test
    void adjacentSlotsDoNotOverlap() {
        schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        schedule.addSlot(FRIDAY, LocalTime.of(11, 30), master);

        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).hasSize(2);
    }

    @Test
    void slotInThePastIsAllowedForMaster() {
        var past = LocalDate.of(2026, 9, 20);

        var slot = schedule.addSlot(past, LocalTime.of(10, 0), master);

        assertThat(slot.day()).isEqualTo(past);
    }

    @Test
    void changingSlotDurationDoesNotAffectExistingSlots() {
        schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        settings.setSlotDuration(90, master);
        schedule.addSlot(FRIDAY, LocalTime.of(12, 0), master);

        assertThat(queries.slotsOfDay(FRIDAY, master))
                .extracting(SlotView::durationMinutes)
                .containsExactly(120, 90);
    }

    @Test
    void freeSlotCanBeMovedWithinItsDay() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);

        var moved = schedule.moveSlot(slot.id(), LocalTime.of(12, 0), master);

        assertThat(moved.id()).isEqualTo(slot.id());
        assertThat(moved.day()).isEqualTo(FRIDAY);
        assertThat(moved.startsAt()).isEqualTo(Instant.parse("2026-10-02T09:00:00Z"));
        assertThat(moved.endsAt()).isEqualTo(Instant.parse("2026-10-02T11:00:00Z"));
        assertThat(queries.slotsOfDay(FRIDAY, master))
                .extracting(SlotView::startsAt)
                .containsExactly(moved.startsAt());
    }

    @Test
    void movingOntoAnotherSlotIsRejectedByTheDatabase() {
        schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);
        var second = schedule.addSlot(FRIDAY, LocalTime.of(13, 0), master);

        assertThatThrownBy(() -> schedule.moveSlot(second.id(), LocalTime.of(10, 0), master))
                .isInstanceOf(SlotOverlapException.class);
    }

    @Test
    void deletedSlotDisappears() {
        var slot = schedule.addSlot(FRIDAY, LocalTime.of(9, 30), master);

        schedule.deleteSlot(slot.id(), master);

        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).isEmpty();
        assertThatThrownBy(() -> schedule.moveSlot(slot.id(), LocalTime.of(12, 0), master))
                .isInstanceOf(SlotNotFoundException.class);
    }

    @Test
    void clientCannotManageSlotsOrSeeOccupancy() {
        var client = Actor.client(UUID.randomUUID());

        assertThatThrownBy(() -> schedule.addSlot(FRIDAY, LocalTime.of(9, 30), client))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.slotsOfDay(FRIDAY, client)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void periodMustBeOrdered() {
        assertThatThrownBy(() -> queries.freeSlots(FRIDAY, FRIDAY.minusDays(1)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "to"));
    }

    @Test
    void periodQueriesSpanDaysInStartOrderWithInclusiveBounds() {
        var saturday = FRIDAY.plusDays(1);
        var client = Actor.client(UUID.randomUUID());
        var onSaturday = schedule.addSlot(saturday, LocalTime.of(9, 30), master);
        var onFriday = schedule.addSlot(FRIDAY, LocalTime.of(12, 0), master);

        assertThat(queries.slotsOfPeriod(FRIDAY, saturday, master))
                .extracting(SlotView::id)
                .containsExactly(onFriday.id(), onSaturday.id());
        assertThat(queries.freeSlots(FRIDAY, saturday))
                .extracting(SlotView::id)
                .containsExactly(onFriday.id(), onSaturday.id());
        assertThat(queries.freeSlots(FRIDAY, FRIDAY)).extracting(SlotView::id).containsExactly(onFriday.id());
        assertThatThrownBy(() -> queries.slotsOfPeriod(saturday, FRIDAY, master))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> queries.slotsOfPeriod(FRIDAY, saturday, client))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletingUnknownSlotFails() {
        assertThatThrownBy(() -> schedule.deleteSlot(UUID.randomUUID(), master))
                .isInstanceOf(SlotNotFoundException.class);
    }
}
