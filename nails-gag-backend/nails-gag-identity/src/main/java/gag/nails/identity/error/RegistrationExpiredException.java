package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Билет регистрации или привязки просрочен либо уже использован. */
public final class RegistrationExpiredException extends UserFacingException {

    public RegistrationExpiredException() {
        super("identity.registration_expired");
    }
}
