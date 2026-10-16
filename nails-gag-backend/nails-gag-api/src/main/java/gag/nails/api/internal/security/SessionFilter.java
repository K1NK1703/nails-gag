package gag.nails.api.internal.security;

import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.identity.Sessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cookie {@code ng_session} → {@link Sessions#authenticate} → атрибуты запроса
 * {@link ApiRequestAttributes#AUTHENTICATED} и {@link ApiRequestAttributes#SESSION_TOKEN}. Если cookie с этим именем
 * несколько, проверяются первые две ({@link SessionCookie#candidates}), берётся первая живая. Сессии не нашлось
 * (закрыта, истекла, подделана, значение не похоже на токен) — cookie снимается в ответе, запрос идёт дальше
 * анонимным: нужна ли сессия, решает контроллер сигнатурой ({@link CurrentActorResolver}). Токен не логируется.
 */
class SessionFilter extends OncePerRequestFilter {

    private final Sessions sessions;
    private final SessionCookie cookie;

    SessionFilter(Sessions sessions, SessionCookie cookie) {
        this.sessions = sessions;
        this.cookie = cookie;
    }

    /** Первое из значений cookie, у которого есть живая сессия; нашлось — атрибуты запроса заполнены. */
    private boolean authenticate(HttpServletRequest request) {
        for (var token : cookie.candidates(request)) {
            var authenticated = sessions.authenticate(token);
            if (authenticated.isPresent()) {
                request.setAttribute(ApiRequestAttributes.AUTHENTICATED, authenticated.get());
                request.setAttribute(ApiRequestAttributes.SESSION_TOKEN, token);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (cookie.sent(request) && !authenticate(request)) {
            cookie.clear(response);
        }
        chain.doFilter(request, response);
    }
}
