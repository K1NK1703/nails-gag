package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Операция оставила бы систему без незаблокированного аккаунта с изменением раздела «Доступ». */
public final class LastAccessAdminException extends UserFacingException {

    public LastAccessAdminException() {
        super("identity.last_access_admin");
    }
}
