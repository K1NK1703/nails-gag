package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Вход не удался: state не совпал или просрочен, провайдер отказал или не принял код. */
public final class LoginFailedException extends UserFacingException {

    public LoginFailedException() {
        super("identity.login_failed");
    }
}
