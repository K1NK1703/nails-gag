package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.identity.model.LoginPurpose;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/**
 * Маленькие лимиты; у каждого теста свои IP и аккаунты, корзины не пересекаются. Это обязательно: часы перед каждым
 * тестом возвращаются назад, а Bucket4j при времени, идущем назад, корзину не пополняет — чужая пустая корзина
 * осталась бы пустой.
 */
@ApiTest
@TestPropertySource(
        properties = {
            RateLimitTest.AUTH_PER_MINUTE,
            RateLimitTest.ANONYMOUS_PER_MINUTE,
            RateLimitTest.AUTHENTICATED_PER_MINUTE
        })
class RateLimitTest extends ApiTestSupport {

    /** Те же настройки берёт {@link SecurityHeadersTest}: контекст общий. */
    static final String AUTH_PER_MINUTE = "nails-gag.api.rate-limits.auth-per-minute=3";

    static final String ANONYMOUS_PER_MINUTE = "nails-gag.api.rate-limits.anonymous-per-minute=5";
    static final String AUTHENTICATED_PER_MINUTE = "nails-gag.api.rate-limits.authenticated-per-minute=7";

    static final int AUTH = 3;
    static final int ANONYMOUS = 5;
    private static final int AUTHENTICATED = 7;

    /** Адрес входа, которого нет: {@code 404}, но запрос в корзину входа засчитывается. */
    private static final String AUTH_ADDRESS = "/api/auth/x";

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

    @Test
    void authPathsHaveTheirOwnBucket() {
        for (var i = 0; i < AUTH; i++) {
            get(AUTH_ADDRESS, "198.51.100.10").expectStatus().isNotFound();
        }

        get(AUTH_ADDRESS, "198.51.100.10")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .value(
                        "Retry-After",
                        retryAfter -> assertThat(Long.parseLong(retryAfter)).isPositive())
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
        // Остальные адреса с того же IP считаются в другой корзине.
        anonymous("198.51.100.10").expectStatus().isOk();
    }

    @Test
    void startIsLimitedByAuthBucket() {
        for (var i = 0; i < AUTH; i++) {
            start("198.51.100.15").expectStatus().isOk();
        }

        start("198.51.100.15")
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
    }

    @Test
    void signedInRequestToAuthPathUsesIpBucket() {
        var session = signInClient();

        for (var i = 0; i < AUTH; i++) {
            as(session, client.get().uri(AUTH_ADDRESS))
                    .header("X-Forwarded-For", "198.51.100.11")
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
        as(session, client.get().uri(AUTH_ADDRESS))
                .header("X-Forwarded-For", "198.51.100.11")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Корзина входа — по IP: с другого адреса тот же человек проходит, и корзина аккаунта не тронута.
        as(session, client.get().uri(AUTH_ADDRESS))
                .header("X-Forwarded-For", "198.51.100.12")
                .exchange()
                .expectStatus()
                .isNotFound();
        signedIn(session, "198.51.100.11").expectStatus().isOk();
    }

    @Test
    void authPathClassification() {
        // Сам /api/auth — адрес входа.
        for (var i = 0; i < AUTH; i++) {
            get("/api/auth", "198.51.100.13").expectStatus().isNotFound();
        }
        get("/api/auth", "198.51.100.13").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // /api/authx — нет: общая корзина анонима, в ней больше запросов.
        for (var i = 0; i < ANONYMOUS; i++) {
            get("/api/authx", "198.51.100.14").expectStatus().isNotFound();
        }
        get("/api/authx", "198.51.100.14").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseSpec start(String ip) {
        return csrf(client.post().uri("/api/auth/fake/start"))
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", LoginPurpose.LOGIN))
                .exchange();
    }

    private ResponseSpec get(String uri, String ip) {
        return client.get().uri(uri).header("X-Forwarded-For", ip).exchange();
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
