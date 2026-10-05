package gag.nails.booking;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Клиент не пришёл. */
public record BookingNoShow(UUID bookingId, UUID clientId, Instant slotStartsAt, Instant occurredAt)
        implements DomainEvent {}
