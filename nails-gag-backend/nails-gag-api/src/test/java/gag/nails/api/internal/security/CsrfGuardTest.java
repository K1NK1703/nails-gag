package gag.nails.api.internal.security;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

@ApiTest
class CsrfGuardTest extends ApiTestSupport {

    private static final String ECHO = "/api/test/echo";

    @Test
    void postWithoutHeaderIsRejected() {
        rejected(client.post().uri(ECHO).header("Origin", SITE).exchange());
        // Остальные изменяющие методы — так же; фильтр отказывает раньше, чем выясняется, есть ли такой адрес.
        rejected(client.put().uri(ECHO).header("Origin", SITE).exchange());
        rejected(client.patch().uri(ECHO).header("Origin", SITE).exchange());
        rejected(client.delete().uri(ECHO).header("Origin", SITE).exchange());
    }

    @Test
    void postWithForeignOriginIsRejected() {
        rejected(client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .header("Origin", "https://evil.example")
                .exchange());
        // Origin сверяется целиком, а не по началу.
        rejected(client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .header("Origin", SITE + ".evil.example")
                .exchange());
        // Origin: null — у песочницы и некоторых переходов; источник неизвестен.
        rejected(client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .header("Origin", "null")
                .exchange());
    }

    @Test
    void postWithRefererFallbackIsAccepted() {
        client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .header("Referer", SITE + "/register")
                .exchange()
                .expectStatus()
                .isOk();
        // Referer — только с путём на том же origin: похожий хост не проходит.
        rejected(client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .header("Referer", SITE + ".evil.example/register")
                .exchange());
    }

    @Test
    void postWithoutOriginAndRefererIsRejected() {
        rejected(client.post()
                .uri(ECHO)
                .header("X-Requested-With", REQUESTED_WITH)
                .exchange());
    }

    @Test
    void crossSiteFetchMetadataIsRejected() {
        rejected(csrf(client.post().uri(ECHO))
                .header("Sec-Fetch-Site", "cross-site")
                .exchange());
        rejected(csrf(client.post().uri(ECHO))
                .header("Sec-Fetch-Site", "same-site")
                .exchange());
    }

    @Test
    void getWithoutHeaderPasses() {
        client.get().uri("/api/test/maybe").exchange().expectStatus().isOk();
        client.head().uri("/api/test/maybe").exchange().expectStatus().isOk();
        client.options().uri("/api/test/maybe").exchange().expectStatus().isOk();
    }

    @Test
    void validPostPasses() {
        csrf(client.post().uri(ECHO)).exchange().expectStatus().isOk();
        csrf(client.post().uri(ECHO))
                .header("Sec-Fetch-Site", "same-origin")
                .exchange()
                .expectStatus()
                .isOk();
    }

    private static void rejected(ResponseSpec response) {
        response.expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.csrf")
                .jsonPath("$.instance")
                .isEqualTo(ECHO);
    }
}
