package gag.nails.api.internal.security;

import gag.nails.api.internal.ApiProperties;
import gag.nails.api.internal.error.ProblemWriter;
import gag.nails.identity.Sessions;
import jakarta.servlet.Filter;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Цепочка защиты {@code /api/*} (спецификация API, §5.0): заголовки → сессия → лимиты → CSRF. Сами фильтры — не
 * бины: иначе Spring Boot зарегистрировал бы их второй раз, на все адреса. Порядок — после фильтров Spring Boot
 * (наблюдение HTTP с порядком {@code HIGHEST_PRECEDENCE + 1} уже положило {@code traceId} в MDC, и отказы фильтров
 * его несут), но до {@code DispatcherServlet}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityFilters {

    static final String URL_PATTERN = "/api/*";
    static final int HEADERS_ORDER = Ordered.HIGHEST_PRECEDENCE + 100;
    static final int SESSION_ORDER = HEADERS_ORDER + 1;
    static final int RATE_LIMIT_ORDER = HEADERS_ORDER + 2;
    static final int CSRF_ORDER = HEADERS_ORDER + 3;

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        return registration(new SecurityHeadersFilter(), HEADERS_ORDER);
    }

    @Bean
    FilterRegistrationBean<SessionFilter> sessionFilter(Sessions sessions, SessionCookie cookie) {
        return registration(new SessionFilter(sessions, cookie), SESSION_ORDER);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            ApiProperties properties, Clock clock, ProblemWriter problems) {
        var limits = new RateLimits(properties.rateLimits(), clock);
        return registration(new RateLimitFilter(limits, problems), RATE_LIMIT_ORDER);
    }

    @Bean
    FilterRegistrationBean<CsrfFilter> csrfFilter(ApiProperties properties, ProblemWriter problems) {
        return registration(new CsrfFilter(properties.siteOrigin(), problems), CSRF_ORDER);
    }

    private static <F extends Filter> FilterRegistrationBean<F> registration(F filter, int order) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(URL_PATTERN);
        registration.setOrder(order);
        return registration;
    }
}
