package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** К записи уже приложено столько фото-референсов, сколько разрешено. */
public final class ReferencesLimitReachedException extends UserFacingException {

    public ReferencesLimitReachedException(int limit) {
        super("booking.references_limit_reached", Map.of("limit", limit));
    }
}
