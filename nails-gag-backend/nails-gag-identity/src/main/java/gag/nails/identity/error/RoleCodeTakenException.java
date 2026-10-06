package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Роль с таким кодом уже есть. */
public final class RoleCodeTakenException extends UserFacingException {

    public RoleCodeTakenException(String code) {
        super("identity.role_code_taken", Map.of("code", code));
    }
}
