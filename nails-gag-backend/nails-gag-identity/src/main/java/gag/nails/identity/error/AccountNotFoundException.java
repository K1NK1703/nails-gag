package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Аккаунта с таким идентификатором нет. */
public final class AccountNotFoundException extends UserFacingException {

    public AccountNotFoundException(UUID accountId) {
        super("identity.account_not_found", Map.of("accountId", accountId));
    }
}
