package gag.nails.api.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.identity.testing.FakeProfile;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/** Выход: {@code ng_session} закрывается на сервере и снимается в браузере. */
@ApiTest
class SessionCookieTest extends ApiTestSupport {

    @Test
    void logoutClosesSessionAndClearsCookie() {
        var session = signInClient();

        var result = as(session, csrf(client.post().uri("/api/auth/logout")))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .returnResult();

        assertCleared(cookie(result, SESSION_COOKIE));
        assertThat(sessions.authenticate(session.cookie())).isEmpty();
        as(session, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void logoutClearsLeftoverTicket() {
        var session = signInClient();

        var result = as(session, csrf(client.post().uri("/api/auth/logout")))
                .cookie(TICKET_COOKIE, "A".repeat(43))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        var ticket = cookie(result, TICKET_COOKIE);
        assertThat(ticket.getValue()).isEmpty();
        assertThat(ticket.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(ticket.getPath()).isEqualTo("/api/auth");
    }

    @Test
    void logoutWithoutSessionIsNoContent() {
        var result = csrf(client.post().uri("/api/auth/logout"))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        assertCleared(cookie(result, SESSION_COOKIE));
    }

    @Test
    void logoutAllClosesOtherSessions() {
        var subject = uniqueSubject();
        var profile = new FakeProfile(subject, uniquePhone(), null, "Клиент " + subject, null);
        var first = signIn(profile);
        var second = signIn(profile);
        assertThat(second.accountId()).isEqualTo(first.accountId());

        var result = as(first, csrf(client.post().uri("/api/auth/logout-all")))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        assertCleared(cookie(result, SESSION_COOKIE));
        as(second, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        as(first, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        // Без сессии выйти отовсюду нельзя.
        csrf(client.post().uri("/api/auth/logout-all"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    private static void assertCleared(ResponseCookie cookie) {
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.getPath()).isEqualTo("/api");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
    }
}
