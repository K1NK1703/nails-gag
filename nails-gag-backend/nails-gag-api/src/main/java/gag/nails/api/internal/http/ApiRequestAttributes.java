package gag.nails.api.internal.http;

import gag.nails.identity.model.Authenticated;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Атрибуты запроса, которыми обмениваются фильтры, резолверы и обработчик ошибок слоя. */
public final class ApiRequestAttributes {

    /** Вошедший ({@link Authenticated}); кладёт фильтр сессии, нет атрибута — запрос анонимный. */
    public static final String AUTHENTICATED = "gag.nails.api.authenticated";

    private ApiRequestAttributes() {}

    public static Optional<Authenticated> authenticated(HttpServletRequest request) {
        return request.getAttribute(AUTHENTICATED) instanceof Authenticated authenticated
                ? Optional.of(authenticated)
                : Optional.empty();
    }
}
