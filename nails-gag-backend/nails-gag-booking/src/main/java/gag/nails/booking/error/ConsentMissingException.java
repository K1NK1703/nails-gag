package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** У клиента нет согласий на действующие редакции политики и оферты. */
public final class ConsentMissingException extends UserFacingException {

    public ConsentMissingException(UUID clientId) {
        super("booking.consent_missing", Map.of("clientId", clientId));
    }
}
