package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Окно бесплатного переноса закрыто: клиент может только отменить запись или написать мастеру. */
public final class LateRescheduleNotAllowedException extends UserFacingException {

    public LateRescheduleNotAllowedException(UUID bookingId, int windowHours) {
        super("booking.late_reschedule_not_allowed", Map.of("bookingId", bookingId, "windowHours", windowHours));
    }
}
