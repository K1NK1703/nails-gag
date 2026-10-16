package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.time.Duration;
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
