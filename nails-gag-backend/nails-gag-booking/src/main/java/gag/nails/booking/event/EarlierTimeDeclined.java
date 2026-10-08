package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Клиент отказался приехать раньше: время визита прежнее. */
public record EarlierTimeDeclined(UUID bookingId, UUID clientId, Instant slotStartsAt, Instant occurredAt)
        implements DomainEvent {}
