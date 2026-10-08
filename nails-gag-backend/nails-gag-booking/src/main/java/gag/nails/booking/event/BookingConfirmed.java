package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Запись, ждавшая предоплату, подтверждена: предоплата получена либо мастер подтвердил без неё. */
public record BookingConfirmed(
        UUID bookingId, UUID clientId, Instant slotStartsAt, boolean prepaymentReceived, Instant occurredAt)
        implements DomainEvent {}
