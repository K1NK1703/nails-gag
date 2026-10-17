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
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
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

    /**
     * Вошедший по первому из значений cookie, у которого есть живая сессия; нашёлся — атрибуты запроса заполнены.
     * Пусто, если ни одно значение не годно.
     */
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
        MDC.@Nullable MDCCloseable tagged = null;
        if (cookie.sent(request)) {
            var authenticated = authenticate(request);
            if (authenticated.isPresent()) {
                var accountId = authenticated.get().account().id();
                tagged = RequestTelemetry.tagAccount(request, accountId);
            } else {
                cookie.clear(response);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (tagged != null) {
                tagged.close();
            }
        }
    }
}
