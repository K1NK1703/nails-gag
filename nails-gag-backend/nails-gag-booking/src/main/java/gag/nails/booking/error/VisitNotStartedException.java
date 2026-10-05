package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Отметить визит или отказать из-за опоздания можно только после начала слота. */
public final class VisitNotStartedException extends UserFacingException {

    public VisitNotStartedException(UUID bookingId, Instant slotStartsAt) {
        super("booking.visit_not_started", Map.of("bookingId", bookingId, "slotStartsAt", slotStartsAt));
    }
}
