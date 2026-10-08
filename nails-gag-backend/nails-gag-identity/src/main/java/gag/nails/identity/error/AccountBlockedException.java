package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Карточка клиента заблокирована: вход и привязка отклонены. */
public final class AccountBlockedException extends UserFacingException {

    public AccountBlockedException() {
        super("identity.account_blocked");
    }
}
