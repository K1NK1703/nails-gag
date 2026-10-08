package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Клиент согласился приехать раньше: визит начнётся в {@code agreedStart}. */
public record EarlierTimeAccepted(
        UUID bookingId, UUID clientId, Instant slotStartsAt, Instant agreedStart, Instant occurredAt)
        implements DomainEvent {}
