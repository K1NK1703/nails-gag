package gag.nails.booking.event;

import gag.nails.booking.model.RescheduleInitiator;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Запись перенесена на другой слот; {@code slotStartsAt} — начало нового слота.
 *
 * @param initiator кто перенёс запись; модуль записей всегда указывает его, {@code null} бывает только
 *     у события из прежнего конструктора — потребители считают такой перенос сделанным мастером
 */
public record BookingRescheduled(
        UUID bookingId,
        UUID clientId,
        UUID oldSlotId,
        Instant oldSlotStartsAt,
        UUID newSlotId,
        Instant slotStartsAt,
        Instant occurredAt,
        @Nullable RescheduleInitiator initiator)
        implements DomainEvent {

    /** Прежняя форма; инициатор неизвестен ({@code null}). */
    public BookingRescheduled(
            UUID bookingId,
            UUID clientId,
            UUID oldSlotId,
            Instant oldSlotStartsAt,
            UUID newSlotId,
            Instant slotStartsAt,
            Instant occurredAt) {
        this(bookingId, clientId, oldSlotId, oldSlotStartsAt, newSlotId, slotStartsAt, occurredAt, null);
    }
}
