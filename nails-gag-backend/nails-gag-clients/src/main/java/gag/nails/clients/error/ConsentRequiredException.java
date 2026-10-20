package gag.nails.clients.error;

import gag.nails.clients.model.ConsentKind;
import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Согласие, без которого клиента нельзя обслуживать (обработка персональных данных, оферта): отозвать его нельзя. */
public final class ConsentRequiredException extends UserFacingException {

    public ConsentRequiredException(ConsentKind kind) {
        super("clients.consent_required", Map.of("kind", kind));
    }
}
