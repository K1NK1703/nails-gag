package gag.nails.schedule.internal.error;

import gag.nails.common.error.InternalException;
import java.util.UUID;

/**
 * Модуль записей занимает второй слот той же записью (уникальный индекс {@code slot_booking_id_key}):
 * при переносе сначала освобождают старый слот. Ошибка в его коде.
 */
public final class BookingHoldsAnotherSlotException extends InternalException {

    public BookingHoldsAnotherSlotException(UUID bookingId, Throwable cause) {
        super("booking " + bookingId + " already holds a slot: release it before occupying another", cause);
    }
}
