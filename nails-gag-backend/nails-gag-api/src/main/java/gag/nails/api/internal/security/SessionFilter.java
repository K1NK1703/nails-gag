package gag.nails.api.internal.security;

import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.api.internal.observability.RequestTelemetry;
import gag.nails.identity.Sessions;
import gag.nails.identity.model.Authenticated;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cookie {@code ng_session} → {@link Sessions#authenticate} → атрибуты запроса
 * {@link ApiRequestAttributes#AUTHENTICATED} и {@link ApiRequestAttributes#SESSION_TOKEN}. Если cookie с этим именем
 * несколько, проверяются первые две ({@link SessionCookie#candidates}), берётся первая живая. Сессии не нашлось
 * (закрыта, истекла, подделана, значение не похоже на токен) — cookie снимается в ответе, запрос идёт дальше
 * анонимным: нужна ли сессия, решает контроллер сигнатурой ({@link CurrentActorResolver}). Токен не логируется. У
 * вошедшего запрос до конца цепочки размечен аккаунтом ({@link RequestTelemetry#tagAccount}): {@code accountId} в
 * MDC и атрибут span-а запроса.
 */
class SessionFilter extends OncePerRequestFilter {

    private final Sessions sessions;
    private final SessionCookie cookie;

    SessionFilter(Sessions sessions, SessionCookie cookie) {
        this.sessions = sessions;
        this.cookie = cookie;
    }

    /** Первое из значений cookie, у которого есть живая сессия; нашлось — атрибуты запроса заполнены. */
    private Optional<Authenticated> authenticate(HttpServletRequest request) {
        for (var token : cookie.candidates(request)) {
            var authenticated = sessions.authenticate(token);
            if (authenticated.isPresent()) {
                request.setAttribute(ApiRequestAttributes.AUTHENTICATED, authenticated.get());
                request.setAttribute(ApiRequestAttributes.SESSION_TOKEN, token);
                return authenticated;
            }
        }
        return Optional.empty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!cookie.sent(request)) {
            chain.doFilter(request, response);
            return;
        }
        var authenticated = authenticate(request);
        if (authenticated.isEmpty()) {
            cookie.clear(response);
            chain.doFilter(request, response);
            return;
        }
        var tagged = RequestTelemetry.tagAccount(
                request, authenticated.get().account().id());
        try {
            chain.doFilter(request, response);
        } finally {
            tagged.close();
        }
    }
}
