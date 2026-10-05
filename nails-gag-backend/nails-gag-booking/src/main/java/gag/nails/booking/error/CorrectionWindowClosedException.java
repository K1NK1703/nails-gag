package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Срок исправления отметки визита истёк. */
public final class CorrectionWindowClosedException extends UserFacingException {

    public CorrectionWindowClosedException(UUID bookingId, int windowDays) {
        super("booking.correction_window_closed", Map.of("bookingId", bookingId, "windowDays", windowDays));
    }
}
