package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;

/** Ротация пуста: сначала задать шаблоны недель и якорь. */
public final class RotationNotConfiguredException extends UserFacingException {

    public RotationNotConfiguredException() {
        super("schedule.rotation_not_configured");
    }
}
