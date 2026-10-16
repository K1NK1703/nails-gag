package gag.nails.api.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Заголовки защиты на каждом ответе {@code /api} (спецификация API, §5.5), в том числе на отказах следующих фильтров:
 * ставятся до цепочки. {@code Cache-Control: no-store} — умолчание; адрес, ответ которого можно кэшировать, ставит
 * свой {@code Cache-Control}, и тот заменяет этот.
 */
class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        chain.doFilter(request, response);
    }
}
