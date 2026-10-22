package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.Uploads;
import gag.nails.booking.BookingReferences;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/**
 * Фото-референсы к записи (спецификация файлов и портфолио, §6.3): видят те, кто видит запись, прикладывают клиент
 * записи до визита и сотрудник; правила — модуля записей. Картинка референса не кэшируется.
 */
@ApiTest
class ReferencesApiTest extends BookingApiTestSupport {

    @Autowired
    FileStorage files;

    @Test
    void clientAttachesListsAndRemoves() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        var fileId =
                UUID.fromString(field(json(Uploads.upload(client, customer, "reference"), HttpStatus.CREATED), "id")
                        .asString());

        var added = json(attach(customer, booking.id(), fileId), HttpStatus.CREATED);
        assertThat(field(added, "fileId").asString()).isEqualTo(fileId.toString());
        assertThat(field(added, "addedBy").asString()).isEqualTo("CLIENT");
        assertThat(field(added, "width").asInt()).isEqualTo(Uploads.WIDTH);

        var listed = json(as(customer, client.get().uri("/api/bookings/{id}/references", booking.id())));
        assertThat(listed.values())
                .singleElement()
                .satisfies(reference ->
                        assertThat(field(reference, "fileId").asString()).isEqualTo(fileId.toString()));

        as(customer, csrf(client.delete().uri("/api/bookings/{id}/references/{fileId}", booking.id(), fileId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(json(as(customer, client.get().uri("/api/bookings/{id}/references", booking.id())))
                        .values())
                .isEmpty();
    }

    @Test
    void fourthIsConflict() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        for (var i = 0; i < BookingReferences.LIMIT; i++) {
            attach(customer, booking.id(), Uploads.store(files, actor(customer), FilePurpose.REFERENCE))
                    .exchange()
                    .expectStatus()
                    .isCreated();
        }

        var problem = json(
                attach(customer, booking.id(), Uploads.store(files, actor(customer), FilePurpose.REFERENCE)),
                HttpStatus.CONFLICT);

        assertThat(field(problem, "code").asString()).isEqualTo("booking.references_limit_reached");
        assertThat(field(field(problem, "params"), "limit").asInt()).isEqualTo(BookingReferences.LIMIT);
    }

    @Test
    void foreignClientGetsNotFound() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        var fileId = Uploads.store(files, actor(customer), FilePurpose.REFERENCE);
        attach(customer, booking.id(), fileId).exchange().expectStatus().isCreated();
        var stranger = signInClient();

        expectProblem(
                as(stranger, client.get().uri("/api/bookings/{id}/references", booking.id())),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        expectProblem(image(stranger, booking.id(), fileId), HttpStatus.NOT_FOUND, "common.not_found");
        expectProblem(
                attach(stranger, booking.id(), Uploads.store(files, actor(stranger), FilePurpose.REFERENCE)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void referenceImageIsNoStore() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        var fileId = Uploads.store(files, actor(customer), FilePurpose.REFERENCE);
        attach(customer, booking.id(), fileId).exchange().expectStatus().isCreated();

        var result = image(customer, booking.id(), fileId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_JPEG)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(byte[].class)
                .returnResult();
        var body = Objects.requireNonNull(result.getResponseBody());
        assertThat(result.getResponseHeaders().getContentLength()).isEqualTo(body.length);
        // Мастер с чтением «Записей» видит референс той же записи.
        image(signInMaster(), booking.id(), fileId).exchange().expectStatus().isOk();
    }

    /** Файл, который не референс этой записи, не отдаётся и клиенту записи. */
    @Test
    void fileOfAnotherBookingIsNotFound() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        var first = bookFor(clientId, false);
        var second = bookFor(clientId, false);
        var fileId = Uploads.store(files, actor(customer), FilePurpose.REFERENCE);
        attach(customer, first.id(), fileId).exchange().expectStatus().isCreated();

        expectProblem(image(customer, second.id(), fileId), HttpStatus.NOT_FOUND, "common.not_found");
    }

    private RequestHeadersSpec<?> attach(Session session, UUID bookingId, UUID fileId) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/references", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fileId", fileId));
    }

    private RequestHeadersSpec<?> image(Session session, UUID bookingId, UUID fileId) {
        return as(session, client.get().uri("/api/bookings/{id}/references/{fileId}.jpg", bookingId, fileId));
    }
}
