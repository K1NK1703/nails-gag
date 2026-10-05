package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** У клиента уже столько активных записей, сколько разрешено. */
public final class LimitReachedException extends UserFacingException {

    public LimitReachedException(int limit) {
        super("booking.limit_reached", Map.of("limit", limit));
    }
}
