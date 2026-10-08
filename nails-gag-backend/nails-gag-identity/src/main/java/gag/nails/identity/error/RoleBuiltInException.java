package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Встроенную роль нельзя удалить, а у «Разработчика» — понизить уровень раздела «Доступ». */
public final class RoleBuiltInException extends UserFacingException {

    public RoleBuiltInException() {
        super("identity.role_built_in");
    }
}
