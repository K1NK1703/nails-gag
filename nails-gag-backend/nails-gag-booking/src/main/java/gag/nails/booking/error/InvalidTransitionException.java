package gag.nails.booking.error;

import gag.nails.booking.BookingStatus;
import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Действие невозможно в текущем состоянии записи. */
public final class InvalidTransitionException extends UserFacingException {

    public InvalidTransitionException(UUID bookingId, BookingStatus status, String action) {
        super("booking.invalid_transition", Map.of("bookingId", bookingId, "status", status.name(), "action", action));
    }
}
