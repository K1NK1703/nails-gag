package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Провайдер входа недоступен или выключен настройками. */
public final class ProviderUnavailableException extends UserFacingException {

    public ProviderUnavailableException() {
        super("identity.provider_unavailable");
    }
}
