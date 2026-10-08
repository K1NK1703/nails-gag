package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** По записи ждут предоплату до {@code deadline}. */
public record PrepaymentRequested(
        UUID bookingId, UUID clientId, Instant slotStartsAt, Instant deadline, Instant occurredAt)
        implements DomainEvent {}
