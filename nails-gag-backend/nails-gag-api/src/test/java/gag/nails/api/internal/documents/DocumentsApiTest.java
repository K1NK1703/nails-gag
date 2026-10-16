package gag.nails.api.internal.documents;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.clients.model.DocumentKind;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/** Документы (спецификация API, §7.2): списки редакций публичны и кэшируются, текст и новые редакции — по правам. */
@ApiTest
class DocumentsApiTest extends ApiTestSupport {

    /**
     * Даты начала новых редакций: далеко в будущем, чтобы действующая оферта не менялась под соседними тестами, и
     * каждая своя — у документа не бывает двух редакций с одной датой.
     */
    private static final AtomicLong DAYS =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000L));

    private static final LocalDate FAR_FUTURE = LocalDate.of(2200, 1, 1);

    @Test
    void currentVersionsArePublicAndCached() {
        var policy = documents.current(DocumentKind.POLICY);
        var offer = documents.current(DocumentKind.OFFER);

        var body = client.get()
                .uri("/api/documents")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .values(
                        "Cache-Control",
                        values -> assertThat(values)
                                .singleElement()
                                .satisfies(value ->
                                        assertThat(value).contains("public").contains("max-age=300")))
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].kind")
                .isEqualTo("POLICY")
                .jsonPath("$[0].version")
                .isEqualTo(policy.version())
                .jsonPath("$[1].kind")
                .isEqualTo("OFFER")
                .jsonPath("$[1].version")
                .isEqualTo(offer.version())
                .returnResult()
                .getResponseBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("\"text\"");
    }

    @Test
    void historyIsPublic() {
        var history = documents.history(DocumentKind.POLICY);

        client.get()
                .uri("/api/documents/policy/versions")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value("Cache-Control", value -> assertThat(value).contains("max-age=300"))
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(history.size())
                .jsonPath("$[0].version")
                .isEqualTo(history.getFirst().version())
                .jsonPath("$[0].text")
                .doesNotExist();
    }

    @Test
    void developerRegistersVersionWithText() {
        var developer = signInDeveloper();
        var version = uniqueVersion();
        var effectiveFrom = uniqueDate();

        as(developer, newVersion(version, effectiveFrom, "Текст оферты"))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.kind")
                .isEqualTo("OFFER")
                .jsonPath("$.version")
                .isEqualTo(version)
                .jsonPath("$.effectiveFrom")
                .isEqualTo(effectiveFrom.toString());

        as(signInMaster(), client.get().uri("/api/documents/offer/versions/{version}", version))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.version.version")
                .isEqualTo(version)
                .jsonPath("$.text")
                .isEqualTo("Текст оферты");
    }

    @Test
    void masterCannotRegisterVersion() {
        var master = signInMaster();

        as(master, newVersion(uniqueVersion(), uniqueDate(), "Текст"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.access_denied");
    }

    @Test
    void clientReadingVersionTextGetsNotFound() {
        var customer = signInClient();
        var policy = documents.current(DocumentKind.POLICY).version();

        as(customer, client.get().uri("/api/documents/policy/versions/{version}", policy))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void unknownVersionIsNotFound() {
        var master = signInMaster();

        as(master, client.get().uri("/api/documents/policy/versions/{version}", uniqueVersion()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("clients.document_version_not_found");
    }

    @Test
    void duplicateVersionConflicts() {
        var developer = signInDeveloper();
        var version = uniqueVersion();
        as(developer, newVersion(version, uniqueDate(), "Текст"))
                .exchange()
                .expectStatus()
                .isCreated();

        as(developer, newVersion(version, uniqueDate(), "Текст"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("clients.document_version_exists");
    }

    @Test
    void unknownKindIsNotFound() {
        client.get()
                .uri("/api/documents/rules/versions")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    /** {@code POST /api/documents/offer/versions}: редакции оферты не меняют действующую политику для входа. */
    private RequestHeadersSpec<?> newVersion(String version, LocalDate effectiveFrom, String text) {
        return csrf(client.post().uri("/api/documents/offer/versions"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("version", version, "effectiveFrom", effectiveFrom.toString(), "text", text));
    }

    private static String uniqueVersion() {
        return "api-" + UUID.randomUUID();
    }

    private static LocalDate uniqueDate() {
        return FAR_FUTURE.plusDays(DAYS.getAndIncrement());
    }
}
