package gag.nails.booking.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Прежний конструктор события переноса: те же поля на тех же местах, инициатор неизвестен. */
class BookingRescheduledTest {

    @Test
    void legacyConstructorHasNullInitiator() {
        var bookingId = UUID.fromString("019a0d00-0000-7000-8000-000000000001");
        var clientId = UUID.fromString("019a0d00-0000-7000-8000-000000000002");
        var oldSlotId = UUID.fromString("019a0d00-0000-7000-8000-000000000003");
        var newSlotId = UUID.fromString("019a0d00-0000-7000-8000-000000000004");
        var oldSlotStartsAt = Instant.parse("2026-10-05T09:00:00Z");
        var slotStartsAt = Instant.parse("2026-10-06T12:00:00Z");
        var occurredAt = Instant.parse("2026-10-01T10:00:00Z");

        var legacy = new BookingRescheduled(
                bookingId, clientId, oldSlotId, oldSlotStartsAt, newSlotId, slotStartsAt, occurredAt);

        assertThat(legacy)
                .isEqualTo(new BookingRescheduled(
                        bookingId, clientId, oldSlotId, oldSlotStartsAt, newSlotId, slotStartsAt, occurredAt, null));
        assertThat(legacy.initiator()).isNull();
    }
}
