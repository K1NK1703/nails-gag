package gag.nails.api.internal.security;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

@ApiTest
class SecurityHeadersTest extends ApiTestSupport {

    @Test
    void apiResponsesCarryNoSniffReferrerPolicyAndNoStore() {
        // Успешный ответ, ошибка контроллера и отказ фильтра — заголовки одни и те же.
        secured(client.get().uri("/api/test/maybe").exchange()).expectStatus().isOk();
        secured(client.get().uri("/api/nope").exchange()).expectStatus().isNotFound();
        secured(client.post().uri("/api/test/echo").exchange()).expectStatus().isForbidden();
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
