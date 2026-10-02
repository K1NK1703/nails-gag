package gag.nails.schedule.internal.error;

import gag.nails.common.error.InternalException;
import java.util.UUID;

/** Модуль записей освобождает слот, который занят не этой записью (или не занят): ошибка в его коде. */
public final class SlotNotHeldByBookingException extends InternalException {

    public SlotNotHeldByBookingException(UUID slotId, UUID bookingId) {
        super("slot " + slotId + " is not held by booking " + bookingId);
    }
}
