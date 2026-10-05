package gag.nails.booking;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Мастер предложила приехать раньше, к {@code proposedStart}. */
public record EarlierTimeProposed(
        UUID bookingId, UUID clientId, Instant slotStartsAt, Instant proposedStart, Instant occurredAt)
        implements DomainEvent {}
