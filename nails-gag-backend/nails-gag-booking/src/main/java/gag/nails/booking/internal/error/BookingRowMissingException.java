package gag.nails.booking.internal.error;

import gag.nails.common.error.InternalException;

/** Обязательной строки нет в базе: настроек (не применён seed) или блокировки клиента. */
public final class BookingRowMissingException extends InternalException {

    public BookingRowMissingException(String what) {
        super("required row is missing: " + what);
    }
}
