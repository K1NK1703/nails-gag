package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.BodyContentSpec;

@ApiTest
class JsonFormatTest extends ApiTestSupport {

    @Test
    void instantIsUtcWithZ() {
        client.get()
                .uri("/api/test/json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.instant")
                .isEqualTo("2026-10-08T11:30:00Z")
                .jsonPath("$.date")
                .isEqualTo("2026-10-08");
    }

    @Test
    void localTimeWithoutSecondsIsHoursAndMinutes() {
        client.get()
                .uri("/api/test/json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.time")
                .isEqualTo("10:00")
                .jsonPath("$.timeWithSeconds")
                .isEqualTo("10:00:30");

        echoTime("\"10:15\"").jsonPath("$.time").isEqualTo("10:15");
        echoTime("\"10:15:00\"").jsonPath("$.time").isEqualTo("10:15");
        echoTime("\"10:15:45\"").jsonPath("$.time").isEqualTo("10:15:45");
    }

    @Test
    void bigDecimalIsPlain() {
        var body = jsonSample();

        assertThat(body).contains("\"price\":1500.00").contains("\"amount\":1000");
    }

    @Test
    void nullIsWrittenExplicitly() {
        var body = jsonSample();

        assertThat(body).contains("\"comment\":null");
    }

    @Test
    void unknownPropertyInBodyIsRejected() {
        csrf(client.post().uri("/api/test/json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"time\":\"10:00\",\"tmie\":\"11:00\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("tmie")
                .jsonPath("$.errors[0].reason")
                .isEqualTo("unknown property");
    }

    private String jsonSample() {
        var bytes = client.get()
                .uri("/api/test/json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(bytes).isNotNull();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private BodyContentSpec echoTime(String time) {
        return csrf(client.post().uri("/api/test/json"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"time\":" + time + "}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }
}
