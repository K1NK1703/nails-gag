package gag.nails.identity.error;

import gag.nails.common.error.UserFacingException;

/** Провайдер входа недоступен или выключен настройками. */
public final class ProviderUnavailableException extends UserFacingException {

    public ProviderUnavailableException() {
        super("identity.provider_unavailable");
    }

    /** С причиной (сеть, таймаут, нечитаемый ответ) — для логов; текст причины не содержит тела ответа. */
    public ProviderUnavailableException(Throwable cause) {
        this();
        initCause(cause);
    }
}
