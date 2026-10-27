package gag.nails.api.internal.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.api.Uploads;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.schedule.ScheduleService;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Портфолио (спецификация файлов и портфолио, §6.2): публичный список и картинки опубликованных работ, ведение работ —
 * по разделу «Портфолио». Правило публичности проверяет модуль; здесь — что адреса его соблюдают и как кэшируются.
 * База общая с другими тестами: работы других тестов могут быть в списке, поэтому проверяются только свои.
 */
@ApiTest
class PortfolioApiTest extends ApiTestSupport {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    /** Начало JPEG: {@code FF D8 FF}. */
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    @Autowired
    FileStorage files;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Test
    void publicListIsPagedAndCached() {
        var work = addWork(signInMaster(), "EXAMPLE", null);

        var first = client.get()
                .uri("/api/portfolio")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(
                        "Cache-Control",
                        value -> assertThat(value).contains("public").contains("max-age=300"))
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(field(Objects.requireNonNull(first), "size").asInt()).isEqualTo(12);
        assertThat(field(first, "page").asInt()).isZero();
        assertThat(field(first, "items").size()).isLessThanOrEqualTo(12);

        // Своя работа — среди опубликованных; поля — без клиента, записи и источника.
        assertThat(publishedIds()).contains(UUID.fromString(field(work, "id").asString()));
        var total =
                field(json(client.get().uri("/api/portfolio?size=1")), "total").asLong();
        var last = json(client.get().uri("/api/portfolio?page={page}&size=1", total - 1));
        var item = field(last, "items").path(0);
        assertThat(field(last, "items").size()).isEqualTo(1);
        assertThat(field(item, "fileId").asString()).isNotEmpty();
        assertThat(item.has("width")).isTrue();
        assertThat(item.has("source")).isFalse();
        assertThat(item.has("clientId")).isFalse();
    }

    @Test
    void masterAddsShowsHidesReordersDeletes() {
        var master = signInMaster();
        var fileId = UUID.fromString(field(json(Uploads.upload(client, master, "portfolio"), HttpStatus.CREATED), "id")
                .asString());

        var added = json(
                as(master, csrf(client.post().uri("/api/portfolio/works")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(workBody(fileId, "Нюдовый маникюр", "EXAMPLE", null)),
                HttpStatus.CREATED);
        var workId = UUID.fromString(field(added, "id").asString());
        assertThat(field(added, "visible").asBoolean()).isTrue();
        assertThat(field(added, "published").asBoolean()).isTrue();

        var updated = json(as(master, csrf(client.patch().uri("/api/portfolio/works/{id}", workId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(updateBody("Френч", "Подпись", "CLIENT_OUTSIDE_SITE")));
        assertThat(field(updated, "alt").asString()).isEqualTo("Френч");
        assertThat(field(updated, "caption").asString()).isEqualTo("Подпись");
        assertThat(field(updated, "consent").asString()).isEqualTo("OUTSIDE_SITE");

        workAction(master, workId, "hide").exchange().expectStatus().isNoContent();
        assertThat(field(work(master, workId), "visible").asBoolean()).isFalse();
        workAction(master, workId, "show").exchange().expectStatus().isNoContent();
        assertThat(field(work(master, workId), "visible").asBoolean()).isTrue();

        // Порядок — всех работ сразу: своя работа переезжает в начало.
        var order = new ArrayList<>(ids(json(as(master, client.get().uri("/api/portfolio/works")))));
        order.remove(workId);
        order.addFirst(workId);
        as(master, csrf(client.put().uri("/api/portfolio/order")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderedWorkIds", order))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(ids(json(as(master, client.get().uri("/api/portfolio/works")))))
                .containsExactlyElementsOf(order);

        as(master, csrf(client.delete().uri("/api/portfolio/works/{id}", workId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        expectProblem(
                as(master, client.get().uri("/api/portfolio/works/{id}", workId)),
                HttpStatus.NOT_FOUND,
                "portfolio.work_not_found");
        expectProblem(
                as(master, client.get().uri("/api/portfolio/images/{fileId}/480.jpg", fileId)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void altLongerThan200IsRejected() {
        var master = signInMaster();
        var fileId = Uploads.store(files, actor(master), FilePurpose.PORTFOLIO);

        var problem = json(
                as(master, csrf(client.post().uri("/api/portfolio/works")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(workBody(fileId, "а".repeat(201), "EXAMPLE", null)),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("alt");
    }

    @Test
    void publishedImageIsPublicWithHourCache() {
        var work = addWork(signInMaster(), "EXAMPLE", null);
        var fileId = field(work, "fileId").asString();

        for (var width : List.of(480, 960, 1600)) {
            var result = client.get()
                    .uri("/api/portfolio/images/{fileId}/{width}.jpg", fileId, width)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .contentType(MediaType.IMAGE_JPEG)
                    .expectHeader()
                    .value(
                            "Cache-Control",
                            value -> assertThat(value).contains("public").contains("max-age=3600"))
                    .expectBody(byte[].class)
                    .returnResult();
            var body = Objects.requireNonNull(result.getResponseBody());
            assertThat(body).startsWith(JPEG_SIGNATURE);
            assertThat(result.getResponseHeaders().getContentLength()).isEqualTo(body.length);
        }
        expectProblem(
                client.get().uri("/api/portfolio/images/{fileId}/500.jpg", fileId),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    /** {@code Range} не поддерживается: ответ — вся картинка, а не {@code 206} и не ошибка. */
    @Test
    void rangeIsIgnored() {
        var fileId = field(addWork(signInMaster(), "EXAMPLE", null), "fileId").asString();

        var result = client.get()
                .uri("/api/portfolio/images/{fileId}/480.jpg", fileId)
                .header("Range", "bytes=0-0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist("Accept-Ranges")
                .expectBody(byte[].class)
                .returnResult();

        var body = Objects.requireNonNull(result.getResponseBody());
        assertThat(body).startsWith(JPEG_SIGNATURE);
        assertThat(result.getResponseHeaders().getContentLength()).isEqualTo(body.length);
    }

    /** Содержимое неизменно: {@code ETag} по файлу и ширине, повтор с ним — {@code 304} с тем же кэшем. */
    @Test
    void repeatedRequestWithEtagIsNotModified() {
        var master = signInMaster();
        var work = addWork(master, "EXAMPLE", null);
        var fileId = field(work, "fileId").asString();
        var etag = "\"" + fileId + "-960\"";

        client.get()
                .uri("/api/portfolio/images/{fileId}/960.jpg", fileId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", etag);
        client.get()
                .uri("/api/portfolio/images/{fileId}/960.jpg", fileId)
                .header("If-None-Match", etag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectHeader()
                .valueEquals("ETag", etag)
                .expectHeader()
                .value("Cache-Control", value -> assertThat(value).contains("max-age=3600"));

        // Невидимая картинка — 404 и с ETag: видимость проверяется раньше.
        workAction(master, UUID.fromString(field(work, "id").asString()), "hide")
                .exchange()
                .expectStatus()
                .isNoContent();
        expectProblem(
                client.get()
                        .uri("/api/portfolio/images/{fileId}/960.jpg", fileId)
                        .header("If-None-Match", etag),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void hiddenImageIsNotFoundForAnonymousButVisibleToStaff() {
        var master = signInMaster();
        var work = addWork(master, "EXAMPLE", null);
        workAction(master, UUID.fromString(field(work, "id").asString()), "hide")
                .exchange()
                .expectStatus()
                .isNoContent();
        var fileId = field(work, "fileId").asString();

        expectProblem(
                client.get().uri("/api/portfolio/images/{fileId}/480.jpg", fileId),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        expectProblem(
                as(signInClient(), client.get().uri("/api/portfolio/images/{fileId}/480.jpg", fileId)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        as(master, client.get().uri("/api/portfolio/images/{fileId}/480.jpg", fileId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_JPEG)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
    }

    @Test
    void withdrawnConsentRemovesImageAtOnce() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        var kind = ConsentKind.PHOTO_PUBLICATION;
        clientService.recordConsent(
                clientId,
                new RecordConsent(kind, documents.current(kind.document()).version(), ConsentSource.SITE_CHECKBOX),
                actor(customer));
        var work = addWork(signInMaster(), "CLIENT", bookingOf(clientId));
        var workId = UUID.fromString(field(work, "id").asString());
        var image = "/api/portfolio/images/" + field(work, "fileId").asString() + "/960.jpg";
        assertThat(field(work, "published").asBoolean()).isTrue();
        client.get().uri(image).exchange().expectStatus().isOk();
        assertThat(publishedIds()).contains(workId);

        as(customer, csrf(client.delete().uri("/api/clients/{id}/consents/photo-publication", clientId)))
                .exchange()
                .expectStatus()
                .isNoContent();

        expectProblem(client.get().uri(image), HttpStatus.NOT_FOUND, "common.not_found");
        assertThat(publishedIds()).doesNotContain(workId);
    }

    @Test
    void exampleNeedsNoConsent() {
        var work = addWork(signInMaster(), "EXAMPLE", null);

        assertThat(field(work, "consent").asString()).isEqualTo("NOT_NEEDED");
        assertThat(field(work, "published").asBoolean()).isTrue();
        assertThat(publishedIds()).contains(UUID.fromString(field(work, "id").asString()));
    }

    @Test
    void clientCannotManageWorks() {
        var customer = signInClient();
        var fileId = Uploads.store(files, actor(customer), FilePurpose.REFERENCE);

        expectProblem(
                as(customer, csrf(client.post().uri("/api/portfolio/works")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(workBody(fileId, "Работа", "EXAMPLE", null)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        expectProblem(as(customer, client.get().uri("/api/portfolio/works")), HttpStatus.NOT_FOUND, "common.not_found");
    }

    /** Работа, которую добавил {@code master} через HTTP, с его загруженным фото. */
    private JsonNode addWork(Session master, String source, @Nullable UUID bookingId) {
        var fileId = Uploads.store(files, actor(master), FilePurpose.PORTFOLIO);
        return json(
                as(master, csrf(client.post().uri("/api/portfolio/works")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(workBody(fileId, "Работа " + fileId, source, bookingId)),
                HttpStatus.CREATED);
    }

    private JsonNode work(Session master, UUID workId) {
        return json(as(master, client.get().uri("/api/portfolio/works/{id}", workId)));
    }

    private RequestHeadersSpec<?> workAction(Session master, UUID workId, String action) {
        return as(master, csrf(client.post().uri("/api/portfolio/works/{id}/" + action, workId)));
    }

    /** Все опубликованные работы, по страницам до {@code total}. */
    private List<UUID> publishedIds() {
        var all = new ArrayList<UUID>();
        long total;
        var page = 0;
        do {
            var body = json(client.get().uri("/api/portfolio?page={page}&size=200", page++));
            total = field(body, "total").asLong();
            var items = ids(field(body, "items"));
            all.addAll(items);
            if (items.isEmpty()) {
                break;
            }
        } while (all.size() < total);
        return all;
    }

    /** Запись клиента на свой день в полдень; её заводит разработчик. */
    private UUID bookingOf(UUID clientId) {
        var slot = schedule.addSlot(freshDay(), NOON, developerActor());
        return bookings.createByMaster(
                        new CreateBooking(clientId, slot.id(), mainService().id(), List.of(), null),
                        false,
                        developerActor())
                .id();
    }

    private static Map<String, @Nullable Object> workBody(
            UUID fileId, String alt, String source, @Nullable UUID bookingId) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("fileId", fileId);
        body.put("alt", alt);
        body.put("caption", null);
        body.put("source", source);
        body.put("bookingId", bookingId);
        return body;
    }

    private static Map<String, @Nullable Object> updateBody(String alt, @Nullable String caption, String source) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("alt", alt);
        body.put("caption", caption);
        body.put("source", source);
        body.put("bookingId", null);
        return body;
    }
}
