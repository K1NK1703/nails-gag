package gag.nails.api.internal.security;

import gag.nails.api.internal.error.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Защита от CSRF без токенов (спецификация API, §5.2) для всех методов, кроме {@code GET}, {@code HEAD} и
 * {@code OPTIONS}: заголовок {@code X-Requested-With: nails.gag} (произвольный заголовок кросс-сайтовый запрос без
 * CORS не поставит) и источник запроса — {@code Origin} равен {@code site-origin}, а без {@code Origin} —
 * {@code Referer} с путём на нём; нет ни того ни другого — отказ. {@code Sec-Fetch-Site}, если прислан, должен быть
 * {@code same-origin}. Отказ — {@code 403 common.csrf}.
 */
class CsrfFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CsrfFilter.class);

    static final String CODE = "common.csrf";
    static final String REQUESTED_WITH_HEADER = "X-Requested-With";
    static final String REQUESTED_WITH = "nails.gag";
    static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";
    static final String SAME_ORIGIN = "same-origin";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String siteOrigin;
    private final ProblemWriter problems;

    CsrfFilter(String siteOrigin, ProblemWriter problems) {
        this.siteOrigin = siteOrigin;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        var violation = violation(request);
        if (violation != null) {
            log.warn("{} {} -> 403 {}: {}", request.getMethod(), request.getRequestURI(), CODE, violation);
            problems.write(request, response, HttpStatus.FORBIDDEN, CODE);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Что не так с запросом, или {@code null}, если он с сайта. Значения заголовков в причину не попадают. */
    private @Nullable String violation(HttpServletRequest request) {
        if (!REQUESTED_WITH.equals(request.getHeader(REQUESTED_WITH_HEADER))) {
            return "no " + REQUESTED_WITH_HEADER;
        }
        var fetchSite = request.getHeader(FETCH_SITE_HEADER);
        if (fetchSite != null && !SAME_ORIGIN.equals(fetchSite)) {
            return FETCH_SITE_HEADER + " is not " + SAME_ORIGIN;
        }
        var origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            return siteOrigin.equals(origin) ? null : "foreign Origin";
        }
        var referer = request.getHeader(HttpHeaders.REFERER);
        if (referer != null) {
            return referer.startsWith(siteOrigin + "/") ? null : "foreign Referer";
        }
        return "neither Origin nor Referer";
    }
}
