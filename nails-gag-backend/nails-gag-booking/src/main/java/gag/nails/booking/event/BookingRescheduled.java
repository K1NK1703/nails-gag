package gag.nails.booking.event;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Запись перенесена на другой слот; {@code slotStartsAt} — начало нового слота. */
public record BookingRescheduled(
        UUID bookingId,
        UUID clientId,
        UUID oldSlotId,
        Instant oldSlotStartsAt,
        UUID newSlotId,
        Instant slotStartsAt,
        Instant occurredAt)
        implements DomainEvent {}
