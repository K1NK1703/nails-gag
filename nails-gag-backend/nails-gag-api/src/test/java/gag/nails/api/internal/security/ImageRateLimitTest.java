package gag.nails.api.internal.security;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/**
 * Картинки портфолио — в своей корзине по IP (спецификация файлов и портфолио, §6.2): лендинг запрашивает их десятками,
 * и общая анонимная корзина на них не тратится. Лимиты маленькие, у каждого теста свои IP.
 */
@ApiTest
@TestPropertySource(
        properties = {
            "nails-gag.api.rate-limits.anonymous-per-minute=" + ImageRateLimitTest.ANONYMOUS,
            "nails-gag.api.rate-limits.images-per-minute=" + ImageRateLimitTest.IMAGES
        })
class ImageRateLimitTest extends ApiTestSupport {

    static final int ANONYMOUS = 2;
    static final int IMAGES = 4;

    @Test
    void imagesDoNotSpendAnonymousBucket() {
        for (var i = 0; i < IMAGES; i++) {
            // Картинки нет — 404, но запрос засчитан в корзину картинок.
            image("198.51.100.30", UUID.randomUUID()).expectStatus().isNotFound();
        }

        for (var i = 0; i < ANONYMOUS; i++) {
            anonymous("198.51.100.30").expectStatus().isOk();
        }
    }

    @Test
    void imageBucketRunsOut() {
        for (var i = 0; i < IMAGES; i++) {
            image("198.51.100.31", UUID.randomUUID()).expectStatus().isNotFound();
        }

        image("198.51.100.31", UUID.randomUUID())
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
        // Корзина по IP: с другого адреса картинки идут.
        image("198.51.100.32", UUID.randomUUID()).expectStatus().isNotFound();
    }

    /** Вошедший тоже берёт картинки из корзины по IP, а не из корзины аккаунта. */
    @Test
    void signedInImagesUseIpBucket() {
        var session = signInClient();
        for (var i = 0; i < IMAGES; i++) {
            as(session, client.get().uri("/api/portfolio/images/{fileId}/480.jpg", UUID.randomUUID()))
                    .header("X-Forwarded-For", "198.51.100.33")
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }

        image("198.51.100.33", UUID.randomUUID()).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Своя корзина — только у чтения картинок: другой метод на том же адресе идёт в общую корзину. */
    @Test
    void otherMethodsOnImageAddressSpendAnonymousBucket() {
        for (var i = 0; i < ANONYMOUS; i++) {
            csrf(client.post().uri("/api/portfolio/images/{fileId}/480.jpg", UUID.randomUUID()))
                    .header("X-Forwarded-For", "198.51.100.34")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        anonymous("198.51.100.34").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        image("198.51.100.34", UUID.randomUUID()).expectStatus().isNotFound();
    }

    private ResponseSpec image(String ip, UUID fileId) {
        return client.get()
                .uri("/api/portfolio/images/{fileId}/480.jpg", fileId)
                .header("X-Forwarded-For", ip)
                .exchange();
    }

    private ResponseSpec anonymous(String ip) {
        return client.get().uri("/api/test/maybe").header("X-Forwarded-For", ip).exchange();
    }
}
