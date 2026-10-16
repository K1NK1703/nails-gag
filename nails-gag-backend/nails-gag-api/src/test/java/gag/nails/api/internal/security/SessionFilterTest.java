package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

@ApiTest
class SessionFilterTest extends ApiTestSupport {

    @Test
    void noCookieOnRequiredActorIsUnauthenticated() {
        client.get()
                .uri("/api/test/whoami")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    @Test
    void validCookieResolvesActor() {
        var session = signInClient();

        var result = as(session, client.get().uri("/api/test/whoami"))
                .header("X-Forwarded-For", "203.0.113.10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(session.accountId().toString())
                .jsonPath("$.staff")
                .isEqualTo(false)
                .jsonPath("$.ip")
                .isEqualTo("203.0.113.10")
                .returnResult();

        assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE)).isNull();
    }

    @Test
    void unknownCookieIsClearedAndRequestIsAnonymous() {
        var result = client.get()
                .uri("/api/test/maybe")
                .cookie(SESSION_COOKIE, "not-a-session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEmpty()
                .returnResult();

        var cleared = cookie(result, SESSION_COOKIE);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cleared.getPath()).isEqualTo("/api");
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(cleared.isSecure()).isTrue();
        assertThat(cleared.getSameSite()).isEqualTo("Lax");
    }

    /**
     * Соседний поддомен может подбросить вторую cookie с тем же именем, и браузер пришлёт обе. Живая сессия находится
     * среди первых двух значений, а cookie при этом не снимается.
     */
    @Test
    void duplicateCookieUsesTheLiveOne() {
        var session = signInClient();
        var stale = "A".repeat(43);

        for (var header : List.of(
                SESSION_COOKIE + "=" + stale + "; " + SESSION_COOKIE + "=" + session.cookie(),
                SESSION_COOKIE + "=" + session.cookie() + "; " + SESSION_COOKIE + "=" + stale)) {
            var result = client.get()
                    .uri("/api/test/whoami")
                    .header("Cookie", header)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.accountId")
                    .isEqualTo(session.accountId().toString())
                    .returnResult();
            assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE)).isNull();
        }

        // Ни одно значение не годится — cookie снимается.
        var result = client.get()
                .uri("/api/test/maybe")
                .header("Cookie", SESSION_COOKIE + "=" + stale + "; " + SESSION_COOKIE + "=" + "B".repeat(43))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEmpty()
                .returnResult();
        assertThat(cookie(result, SESSION_COOKIE).getMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void closedSessionIsUnauthenticated() {
        var session = signInClient();
        sessions.close(session.cookie());

        var result = as(session, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated")
                .returnResult();

        assertThat(cookie(result, SESSION_COOKIE).getMaxAge()).isEqualTo(Duration.ZERO);
    }
}
