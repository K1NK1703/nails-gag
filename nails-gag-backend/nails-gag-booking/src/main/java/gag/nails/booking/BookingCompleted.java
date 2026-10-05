package gag.nails.booking;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Визит отмечен выполненным. */
public record BookingCompleted(UUID bookingId, UUID clientId, Instant slotStartsAt, Instant occurredAt)
        implements DomainEvent {}
