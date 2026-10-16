package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/** Маленькие лимиты; у каждого теста свои IP и аккаунты, корзины не пересекаются. */
@ApiTest
@TestPropertySource(
        properties = {
            "nails-gag.api.rate-limits.auth-per-minute=3",
            "nails-gag.api.rate-limits.anonymous-per-minute=5",
            "nails-gag.api.rate-limits.authenticated-per-minute=7"
        })
class RateLimitTest extends ApiTestSupport {

    private static final int ANONYMOUS = 5;
    private static final int AUTHENTICATED = 7;

    @Test
    void anonymousIpIsLimitedWithRetryAfter() {
        for (var i = 0; i < ANONYMOUS; i++) {
            anonymous("198.51.100.1").expectStatus().isOk();
        }

        anonymous("198.51.100.1")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .value(
                        "Retry-After",
                        retryAfter -> assertThat(Long.parseLong(retryAfter)).isPositive())
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
    }

    @Test
    void otherIpIsNotAffected() {
        for (var i = 0; i < ANONYMOUS; i++) {
            anonymous("198.51.100.2").expectStatus().isOk();
        }
        anonymous("198.51.100.2").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        anonymous("198.51.100.3").expectStatus().isOk();
    }

    @Test
    void accountBucketIgnoresIp() {
        var session = signInClient();

        for (var i = 0; i < AUTHENTICATED; i++) {
            signedIn(session, "203.0.113." + (20 + i)).expectStatus().isOk();
        }

        signedIn(session, "203.0.113.99")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
        // Корзина IP при этом не тронута: аноним с того же адреса проходит.
        anonymous("203.0.113.99").expectStatus().isOk();
    }

    @Test
    void bucketRefillsAfterAMinute() {
        for (var i = 0; i < ANONYMOUS; i++) {
            anonymous("198.51.100.4").expectStatus().isOk();
        }
        anonymous("198.51.100.4").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        clock.advance(Duration.ofMinutes(1));

        anonymous("198.51.100.4").expectStatus().isOk();
    }

    private ResponseSpec anonymous(String ip) {
        return client.get().uri("/api/test/maybe").header("X-Forwarded-For", ip).exchange();
    }

    private ResponseSpec signedIn(Session session, String ip) {
        return as(session, client.get().uri("/api/test/whoami"))
                .header("X-Forwarded-For", ip)
                .exchange();
    }
}
