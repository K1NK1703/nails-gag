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
 * {@link ApiRequestAttributes#AUTHENTICATED} и {@link ApiRequestAttributes#SESSION_TOKEN}. Сессии не нашлось
 * (закрыта, истекла, подделана) — cookie снимается в ответе, запрос идёт дальше анонимным: нужна ли сессия, решает
 * контроллер сигнатурой ({@link CurrentActorResolver}). Токен не логируется.
 */
class SessionFilter extends OncePerRequestFilter {

    private final Sessions sessions;
    private final SessionCookie cookie;

    SessionFilter(Sessions sessions, SessionCookie cookie) {
        this.sessions = sessions;
        this.cookie = cookie;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = cookie.read(request);
        if (token.isPresent()) {
            var authenticated = sessions.authenticate(token.get());
            if (authenticated.isPresent()) {
                request.setAttribute(ApiRequestAttributes.AUTHENTICATED, authenticated.get());
                request.setAttribute(ApiRequestAttributes.SESSION_TOKEN, token.get());
            } else {
                cookie.clear(response);
            }
        }
        chain.doFilter(request, response);
    }
}
