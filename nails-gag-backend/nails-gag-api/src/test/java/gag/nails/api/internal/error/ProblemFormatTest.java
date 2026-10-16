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
        // Причины — тексты Bean Validation на английском при любом Accept-Language.
        csrf(client.post().uri("/api/test/validated"))
                .header("Accept-Language", "ru-RU")
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
                .isEqualTo("must not be blank")
                .jsonPath("$.errors[1].field")
                .isEqualTo("phone")
                .jsonPath("$.errors[1].reason")
                .isEqualTo("must match \"^\\+7\\d{10}$\"")
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
    void missingParameterNamesIt() {
        client.get()
                .uri("/api/test/param")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors.length()")
                .isEqualTo(1)
                .jsonPath("$.errors[0].field")
                .isEqualTo("count")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("required");
    }

    @Test
    void parameterOfWrongTypeNamesIt() {
        client.get()
                .uri("/api/test/param?count=many")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("count")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("invalid value");
    }

    @Test
    void parameterConstraintNamesTheRequestParameter() {
        client.get()
                .uri("/api/test/min?size=0")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors.length()")
                .isEqualTo(1)
                .jsonPath("$.errors[0].field")
                .isEqualTo("size")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("must be greater than or equal to 1");
    }

    @Test
    void malformedJsonHasNoFieldErrors() {
        csrf(client.post().uri("/api/test/json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"time\":")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors")
                .doesNotExist();
    }

    @Test
    void anonymousAccessDeniedIsUnauthenticated() {
        client.get()
                .uri("/api/test/throw/access-denied")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    /** Не сотрудник не отличает чужое от несуществующего: общий код и никаких параметров. */
    @Test
    void notFoundIsMaskedForNonStaff() {
        var body = client.get()
                .uri("/api/test/throw/booking-not-found")
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

    @Test
    void serverErrorStatusIsKeptWithoutDetails() {
        var body = client.get()
                .uri("/api/test/throw/unavailable")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"code\":\"common.internal\"").doesNotContain(ProblemTestController.SECRET);
    }

    @Test
    void sendErrorIsAnsweredByErrorController() {
        client.get()
                .uri("/api/test/send-error")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.bad_request")
                .jsonPath("$.instance")
                .isEqualTo("/api/test/send-error");

        client.get()
                .uri("/error")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
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
