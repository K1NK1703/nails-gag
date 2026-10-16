package gag.nails.api.internal.error;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@ApiTest
class ProblemFormatTest extends ApiTestSupport {

    @Test
    void validationListsEveryViolation() {
        csrf(client.post().uri("/api/test/validated"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"phone\":\"123\",\"name\":\" \"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors.length()")
                .isEqualTo(2)
                .jsonPath("$.errors[0].field")
                .isEqualTo("name")
                .jsonPath("$.errors[0].reason")
                .isNotEmpty()
                .jsonPath("$.errors[1].field")
                .isEqualTo("phone")
                .jsonPath("$.errors[1].reason")
                .isNotEmpty()
                .jsonPath("$.params")
                .doesNotExist();
    }

    @Test
    void unknownPropertyNamesTheField() {
        csrf(client.post().uri("/api/test/json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"time\":\"10:00\",\"tmie\":\"11:00\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors.length()")
                .isEqualTo(1)
                .jsonPath("$.errors[0].field")
                .isEqualTo("tmie")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("unknown property");

        csrf(client.post().uri("/api/test/json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"time\":\"25:99\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field")
                .isEqualTo("time")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("invalid value");
    }

    @Test
    void moduleValidationBecomesFieldError() {
        client.get()
                .uri("/api/test/throw/validation")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors.length()")
                .isEqualTo(1)
                .jsonPath("$.errors[0].field")
                .isEqualTo("phone")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("must look like +7XXXXXXXXXX")
                .jsonPath("$.params")
                .doesNotExist();
    }

    @Test
    void userFacingErrorCarriesCodeAndParams() {
        client.get()
                .uri("/api/test/throw/limit-reached")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("about:blank")
                .jsonPath("$.title")
                .isEqualTo("Conflict")
                .jsonPath("$.status")
                .isEqualTo(409)
                .jsonPath("$.instance")
                .isEqualTo("/api/test/throw/limit-reached")
                .jsonPath("$.code")
                .isEqualTo("booking.limit_reached")
                .jsonPath("$.params.limit")
                .isEqualTo(3)
                .jsonPath("$.errors")
                .doesNotExist();
    }

    @Test
    void unknownAddressIsNotFound() {
        client.get()
                .uri("/api/nope")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found")
                .jsonPath("$.instance")
                .isEqualTo("/api/nope");
    }

    @Test
    void wrongMethodIsNotAllowed() {
        csrf(client.delete().uri("/api/test/json"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader()
                .value("Allow", allow -> assertThat(allow).contains("GET", "POST"))
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.method_not_allowed");
    }

    @Test
    void unexpectedErrorHidesDetails() {
        var body = client.get()
                .uri("/api/test/throw/internal")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .contains("\"code\":\"common.internal\"")
                .doesNotContain(ProblemTestController.SECRET)
                .doesNotContain("InternalException")
                .doesNotContain("\"detail\"")
                .doesNotContain("\"message\"");
    }

    @Test
    void lockFailureIsBusyWithRetryAfter() {
        var body = client.get()
                .uri("/api/test/throw/lock")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader()
                .valueEquals("Retry-After", "1")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"code\":\"common.busy\"").doesNotContain(ProblemTestController.SECRET);
    }

    @Test
    void problemContentType() {
        client.get()
                .uri("/api/test/throw/limit-reached")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.get()
                .uri("/api/nope")
                .exchange()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        client.get()
                .uri("/api/test/throw/internal")
                .exchange()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
