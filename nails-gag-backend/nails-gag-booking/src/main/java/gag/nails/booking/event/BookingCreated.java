package gag.nails.booking.event;

import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Запись создана; {@code status} — в каком состоянии (подтверждена сразу или ждёт предоплату). */
public record BookingCreated(
        UUID bookingId,
        UUID clientId,
        Instant slotStartsAt,
        BookingStatus status,
        BookingCreator createdBy,
        Instant occurredAt)
        implements DomainEvent {}
