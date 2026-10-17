package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.api.internal.ApiProperties;
import gag.nails.api.internal.observability.RequestTelemetry;
import gag.nails.common.actor.Actor;
import gag.nails.identity.Sessions;
import gag.nails.identity.model.AccountView;
import gag.nails.identity.model.Authenticated;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Разметка запроса аккаунтом в {@link SessionFilter} снимается и тогда, когда цепочка падает. */
class SessionFilterMdcTest {

    private static final ApiProperties PROPERTIES = new ApiProperties(
            "https://nails-gag.ru",
            new ApiProperties.Cookies(true),
            "",
            new ApiProperties.RateLimits(10, 60, 600),
            new ApiProperties.Redirects("/cabinet", "/register", "/link-confirm", "/linked", "/login"));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void clearsAccountFromMdcWhenChainFails() {
        var accountId = UUID.randomUUID();
        var filter = new SessionFilter(new SignedInSessions(accountId), new SessionCookie(PROPERTIES));
        var request = new MockHttpServletRequest("GET", "/api/me");
        request.setCookies(new Cookie(SessionCookie.NAME, "A".repeat(43)));
        var seen = new AtomicReference<String>();
        FilterChain failing = (req, res) -> {
            seen.set(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID));
            throw new ServletException("chain failed");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);

        assertThat(seen).hasValue(accountId.toString());
        assertThat(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID)).isNull();
    }

    /** Любой токен — живая сессия одного и того же аккаунта. */
    private record SignedInSessions(UUID accountId) implements Sessions {

        @Override
        public Optional<Authenticated> authenticate(String token) {
            var account = new AccountView(accountId, null, null, null, List.of(), Map.of(), Instant.EPOCH);
            return Optional.of(new Authenticated(Actor.of(accountId, null, Map.of()), account));
        }

        @Override
        public void close(String token) {}

        @Override
        public void closeAll(Actor actor) {}
    }
}
