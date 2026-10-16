package gag.nails.api.internal.security;

import gag.nails.api.internal.error.ProblemWriter;
import gag.nails.api.internal.http.ApiPaths;
import gag.nails.api.internal.http.ApiRequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Лимиты частоты (спецификация API, §5.3). Корзина: вход ({@code /api/auth/**}) — по IP; запрос с сессией — по
 * аккаунту (известен после {@link SessionFilter}, поэтому этот фильтр после него); остальное — по IP. IP — уже
 * разобранный Tomcat-ом адрес клиента, IPv6 — с точностью до сети /64. Превышение —
 * {@code 429 common.rate_limited} с {@code Retry-After} в целых секундах до пополнения, округлённых вверх. В лог —
 * только вид корзины: ни IP, ни аккаунта.
 */
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String CODE = "common.rate_limited";
    static final String AUTH_PATH = ApiPaths.AUTH;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimits limits;
    private final ProblemWriter problems;

    RateLimitFilter(RateLimits limits, ProblemWriter problems) {
        this.limits = limits;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimits.Kind kind;
        String id;
        var authenticated = ApiRequestAttributes.authenticated(request);
        if (isAuthPath(request)) {
            kind = RateLimits.Kind.AUTH;
            id = ip(request);
        } else if (authenticated.isPresent()) {
            kind = RateLimits.Kind.ACCOUNT;
            id = authenticated.get().account().id().toString();
        } else {
            kind = RateLimits.Kind.ANONYMOUS;
            id = ip(request);
        }
        var probe = limits.tryConsume(kind, id);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        var retryAfter = Math.max(1, Math.ceilDiv(probe.getNanosToWaitForRefill(), NANOS_PER_SECOND));
        log.debug("{} {} -> 429 {} bucket {}", request.getMethod(), request.getRequestURI(), CODE, kind);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        problems.write(request, response, HttpStatus.TOO_MANY_REQUESTS, CODE);
    }

    private static String ip(HttpServletRequest request) {
        return RateLimits.ipKey(Objects.requireNonNullElse(request.getRemoteAddr(), ""));
    }

    /**
     * Путь, нормализованный контейнером (без {@code //}, {@code ..} и процентного кодирования), — чтобы вход не
     * ушёл в общую корзину из-за другого написания того же адреса. Сам {@code /api/auth} — тоже вход,
     * {@code /api/authx} — нет.
     */
    private static boolean isAuthPath(HttpServletRequest request) {
        var pathInfo = request.getPathInfo();
        var path = request.getServletPath() + (pathInfo == null ? "" : pathInfo);
        return path.equals(AUTH_PATH) || path.startsWith(AUTH_PATH + "/");
    }
}
