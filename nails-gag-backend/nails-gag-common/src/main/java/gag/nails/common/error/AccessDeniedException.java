package gag.nails.common.error;

/** Действие не разрешено этому {@code Actor}. */
public final class AccessDeniedException extends UserFacingException {

    public AccessDeniedException() {
        super("common.access_denied");
    }
}
