package gag.nails.api.internal.http;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;

/**
 * Постраничные списки (спецификация API, §4.4) на примере {@code GET /api/clients}: параметры {@code page} и
 * {@code size}, их пределы и ответ {@code Paged}.
 */
@ApiTest
class PagingTest extends ApiTestSupport {

    @Test
    void sizeOverLimitIsRejected() {
        as(signInMaster(), client.get().uri("/api/clients?size=201"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("size");
    }

    @Test
    void secondPageHasRestAndTotal() {
        var lastName = uniqueLastName();
        for (var i = 0; i < 3; i++) {
            createClient(lastName);
        }

        as(signInMaster(), client.get().uri("/api/clients?query={query}&page=1&size=2", lastName))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].lastName")
                .isEqualTo(lastName)
                .jsonPath("$.page")
                .isEqualTo(1)
                .jsonPath("$.size")
                .isEqualTo(2)
                .jsonPath("$.total")
                .isEqualTo(3);
    }

    @Test
    void negativePageIsRejected() {
        as(signInMaster(), client.get().uri("/api/clients?page=-1"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("page");
    }

    @Test
    void queryLongerThan100IsRejected() {
        as(signInMaster(), client.get().uri("/api/clients?query={query}", "а".repeat(101)))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("query");
    }
}
