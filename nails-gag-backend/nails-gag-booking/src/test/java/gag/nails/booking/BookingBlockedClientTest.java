package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.ClientBlockedException;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.SlotView;
import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;

@ModuleTest
class BookingBlockedClientTest extends BookingTestSupport {

    @Test
    void blockedClientCannotBook() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        clients.block(anna, "грубит", master);

        assertThatThrownBy(() -> book(anna, slot))
                .isInstanceOf(ClientBlockedException.class)
                .satisfies(
                        e -> assertThat(((ClientBlockedException) e).params()).containsEntry("clientId", anna));

        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
        assertThat(queries.activeOfClient(anna, master)).isEmpty();
    }

    @Test
    void blockedClientCannotRescheduleButMayCancel() {
        var anna = client("Анна");
        var first = book(anna, slot(MONDAY, "12:00"));
        // Лимит активных записей в seed — одна: вторую ставит мастер.
        var second = bookings.createByMaster(manicure(anna, slot(MONDAY, "14:00")), false, master);
        var free = slot(TUESDAY, "12:00");
        clients.block(anna, null, master);

        assertThatThrownBy(() -> bookings.reschedule(first.id(), free.id(), Actor.client(anna)))
                .isInstanceOf(ClientBlockedException.class);
        assertThat(queries.get(first.id(), master).slotId()).isEqualTo(first.slotId());
        assertThat(scheduleQueries.freeSlots(TUESDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactly(free.id());

        var cancelled = bookings.cancel(second.id(), Actor.client(anna));

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(queries.activeOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactly(first.id());
    }

    @Test
    void masterMayBookBlockedClient() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        clients.block(anna, "не платит", master);

        var booking = bookings.createByMaster(manicure(anna, slot), false, master);

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.createdBy()).isEqualTo(BookingCreator.MASTER);
        assertThat(bookings.reschedule(booking.id(), slot(TUESDAY, "12:00").id(), master)
                        .status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void existingBookingsSurviveBlocking() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);

        clients.block(anna, "грубит", master);

        var kept = queries.get(booking.id(), master);
        assertThat(kept.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(kept.slotId()).isEqualTo(slot.id());
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();
        assertThat(queries.activeOfClient(anna, Actor.client(anna)))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
    }

    @Test
    void unblockedClientBooksAgain() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        clients.block(anna, "грубит", master);
        assertThatThrownBy(() -> book(anna, slot)).isInstanceOf(ClientBlockedException.class);

        clients.unblock(anna, master);

        var booking = book(anna, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();
    }
}
