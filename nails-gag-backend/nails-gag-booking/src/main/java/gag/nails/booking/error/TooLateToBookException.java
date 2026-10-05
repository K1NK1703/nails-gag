package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.time.Instant;
import java.util.Map;

/** До начала слота осталось меньше, чем разрешено для записи клиентом. */
public final class TooLateToBookException extends UserFacingException {

    public TooLateToBookException(Instant slotStartsAt, int cutoffMinutes) {
        super("booking.too_late_to_book", Map.of("slotStartsAt", slotStartsAt, "cutoffMinutes", cutoffMinutes));
    }
}
