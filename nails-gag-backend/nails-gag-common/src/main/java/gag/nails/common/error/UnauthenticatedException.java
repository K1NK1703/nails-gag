package gag.nails.common.error;

/** Адресу нужна сессия, а её нет или она недействительна (закрыта, истекла, cookie подделана). */
public final class UnauthenticatedException extends UserFacingException {

    public UnauthenticatedException() {
        super("common.unauthenticated");
    }
}
