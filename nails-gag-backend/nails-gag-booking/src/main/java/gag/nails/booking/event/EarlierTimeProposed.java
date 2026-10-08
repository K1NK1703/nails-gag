package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Сотрудник предложил приехать раньше, к {@code proposedStart}. */
public record EarlierTimeProposed(
        UUID bookingId, UUID clientId, Instant slotStartsAt, Instant proposedStart, Instant occurredAt)
        implements DomainEvent {}
