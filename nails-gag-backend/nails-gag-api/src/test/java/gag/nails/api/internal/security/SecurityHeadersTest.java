package gag.nails.api.internal.security;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/** Маленькие лимиты, как у {@link RateLimitTest} (контекст общий), — чтобы получить {@code 429}; IP свои. */
@ApiTest
@TestPropertySource(
        properties = {
            RateLimitTest.AUTH_PER_MINUTE,
            RateLimitTest.ANONYMOUS_PER_MINUTE,
            RateLimitTest.AUTHENTICATED_PER_MINUTE
        })
class SecurityHeadersTest extends ApiTestSupport {

    private static final String IP = "198.51.100.200";
    private static final String LIMITED_IP = "198.51.100.201";

    @Test
    void apiResponsesCarryNoSniffReferrerPolicyAndNoStore() {
        // Успешный ответ, ошибка контроллера, отказ резолвера и отказы фильтров — заголовки одни и те же.
        secured(client.get()
                        .uri("/api/test/maybe")
                        .header("X-Forwarded-For", IP)
                        .exchange())
                .expectStatus()
                .isOk();
        secured(client.get().uri("/api/nope").header("X-Forwarded-For", IP).exchange())
                .expectStatus()
                .isNotFound();
        secured(client.get()
                        .uri("/api/test/whoami")
                        .header("X-Forwarded-For", IP)
                        .exchange())
                .expectStatus()
                .isUnauthorized();
        secured(client.post()
                        .uri("/api/test/echo")
                        .header("X-Forwarded-For", IP)
                        .exchange())
                .expectStatus()
                .isForbidden();

        for (var i = 0; i < RateLimitTest.ANONYMOUS; i++) {
            client.get()
                    .uri("/api/test/maybe")
                    .header("X-Forwarded-For", LIMITED_IP)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        secured(client.get()
                        .uri("/api/test/maybe")
                        .header("X-Forwarded-For", LIMITED_IP)
                        .exchange())
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static ResponseSpec secured(ResponseSpec response) {
        return response.expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Referrer-Policy", "same-origin")
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
    }
}
