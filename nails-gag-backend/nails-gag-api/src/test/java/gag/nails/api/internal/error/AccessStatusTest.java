package gag.nails.api.internal.error;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;

/** Не вошёл, нет прав, не существует (спецификация API, §4.6): ответ зависит от того, кто спросил. */
@ApiTest
class AccessStatusTest extends ApiTestSupport {

    @Test
    void deniedForAnonymousIsUnauthenticated() {
        client.get()
                .uri("/api/test/denied")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    @Test
    void deniedForStaffIsForbidden() {
        var master = signInMaster();

        as(master, client.get().uri("/api/test/denied"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.access_denied");
    }

    @Test
    void deniedForClientIsNotFound() {
        var customer = signInClient();

        as(customer, client.get().uri("/api/test/denied"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found")
                .jsonPath("$.params")
                .doesNotExist();
    }

    @Test
    void notFoundKeepsModuleCodeForStaff() {
        var master = signInMaster();

        as(master, client.get().uri("/api/test/throw/booking-not-found"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("booking.booking_not_found")
                .jsonPath("$.params.bookingId")
                .isEqualTo(ProblemTestController.BOOKING_ID.toString());
    }

    @Test
    void notFoundIsMaskedForClient() {
        var customer = signInClient();

        var body = as(customer, client.get().uri("/api/test/throw/booking-not-found"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .contains("\"code\":\"common.not_found\"")
                .doesNotContain("\"params\"")
                .doesNotContain(ProblemTestController.BOOKING_ID.toString());
    }
}
