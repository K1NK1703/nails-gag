package gag.nails.booking;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/** Запись отменена; кто и почему — в {@code reason}. Слот освобождён. */
public record BookingCancelled(
        UUID bookingId, UUID clientId, Instant slotStartsAt, CancelReason reason, Instant occurredAt)
        implements DomainEvent {}
