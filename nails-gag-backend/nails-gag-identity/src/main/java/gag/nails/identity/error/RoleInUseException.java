package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Роль назначена хотя бы одному аккаунту: удалить её нельзя. */
public final class RoleInUseException extends UserFacingException {

    public RoleInUseException(UUID roleId) {
        super("identity.role_in_use", Map.of("roleId", roleId));
    }
}
