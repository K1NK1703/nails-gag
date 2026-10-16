package gag.nails.api.internal.http;

import gag.nails.identity.model.Authenticated;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Атрибуты запроса, которыми обмениваются фильтры, резолверы и обработчик ошибок слоя. */
public final class ApiRequestAttributes {

    /** Вошедший ({@link Authenticated}); кладёт фильтр сессии, нет атрибута — запрос анонимный. */
    public static final String AUTHENTICATED = "gag.nails.api.authenticated";

    /**
     * Токен действующей сессии из cookie; кладёт фильтр сессии вместе с {@link #AUTHENTICATED}. Нужен выходу и
     * повторной проверке сессии после привязки способа входа. В логи и ответы не попадает.
     */
    public static final String SESSION_TOKEN = "gag.nails.api.session-token";

    private ApiRequestAttributes() {}

    public static Optional<Authenticated> authenticated(HttpServletRequest request) {
        return request.getAttribute(AUTHENTICATED) instanceof Authenticated authenticated
                ? Optional.of(authenticated)
                : Optional.empty();
    }

    public static Optional<String> sessionToken(HttpServletRequest request) {
        return request.getAttribute(SESSION_TOKEN) instanceof String token ? Optional.of(token) : Optional.empty();
    }
}
