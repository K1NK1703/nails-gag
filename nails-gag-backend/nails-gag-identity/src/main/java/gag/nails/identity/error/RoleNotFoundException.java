package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Роли с таким идентификатором нет. */
public final class RoleNotFoundException extends UserFacingException {

    public RoleNotFoundException(UUID roleId) {
        super("identity.role_not_found", Map.of("roleId", roleId));
    }
}
