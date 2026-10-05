package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Записи с таким идентификатором нет. */
public final class BookingNotFoundException extends UserFacingException {

    public BookingNotFoundException(UUID bookingId) {
        super("booking.booking_not_found", Map.of("bookingId", bookingId));
    }
}
