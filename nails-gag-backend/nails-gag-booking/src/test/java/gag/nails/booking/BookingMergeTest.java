package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.RegisterClient;
import gag.nails.testsupport.ModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@ModuleTest
class BookingMergeTest extends BookingTestSupport {

    @Test
    void bookingsMoveToTheSurvivorWhenClientsAreMerged() {
        var card = client("Мария");
        var cancelled = bookings.createByMaster(manicure(card, slot(MONDAY, "12:00")), false, master);
        bookings.cancel(cancelled.id(), master);
        var active = book(card, slot(TUESDAY, "12:00"));
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, null))
                .id();

        clients.merge(card, registered, master);

        assertThat(queries.activeOfClient(registered, master)).singleElement().satisfies(booking -> {
            assertThat(booking.id()).isEqualTo(active.id());
            assertThat(booking.clientId()).isEqualTo(registered);
        });
        assertThat(queries.historyOfClient(registered, master))
                .extracting(BookingView::id)
                .containsExactly(cancelled.id());
        assertThat(queries.activeOfClient(card, master)).isEmpty();
        assertThat(queries.historyOfClient(card, master)).isEmpty();
    }
}
