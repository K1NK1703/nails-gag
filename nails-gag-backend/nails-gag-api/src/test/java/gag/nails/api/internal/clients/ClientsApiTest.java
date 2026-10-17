package gag.nails.api.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.schedule.ScheduleService;
import java.time.LocalTime;
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
 * Клиенты (спецификация API, §7.4): карточки и их журналы — сотруднику по разделу «Клиенты», свои — клиенту; заметка
 * и причина блокировки клиенту не видны. Права проверяет модуль; постраничность поиска — в {@code PagingTest}.
 */
@ApiTest
class ClientsApiTest extends ApiTestSupport {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    ClientQueries clientQueries;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Test
    void staffFindsClientByPhoneAndContactPhone() {
        var master = signInMaster();
        var target = createClient(uniqueLastName());
        var contactPhone = uniquePhone();
        clientService.changeContactPhone(target.id(), contactPhone, developerActor());

        assertThat(searchIds(master, Objects.requireNonNull(target.phone()))).contains(target.id());
        assertThat(searchIds(master, contactPhone)).contains(target.id());
    }

    @Test
    void clientCannotSearch() {
        as(signInClient(), client.get().uri("/api/clients?query={query}", uniqueLastName()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void masterCreatesClient() {
        var phone = uniquePhone();
        var lastName = uniqueLastName();

        as(signInMaster(), csrf(client.post().uri("/api/clients")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateClient("Анна", lastName, null, phone, null, null))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.firstName")
                .isEqualTo("Анна")
                .jsonPath("$.lastName")
                .isEqualTo(lastName)
                .jsonPath("$.phone")
                .isEqualTo(phone)
                .jsonPath("$.source")
                .isEqualTo("ADDED_BY_MASTER");
    }

    @Test
    void clientReadsOwnCardWithoutStaffFields() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        clientService.updateMasterNote(clientId, "Только для мастера", developerActor());
        clientService.setPrepaymentRequired(clientId, true, "Поздняя отмена", developerActor());

        var details = details(customer, clientId);

        assertThat(details.path("client").path("id").asString()).isEqualTo(clientId.toString());
        assertThat(details.path("masterNote").isNull()).isTrue();
        assertThat(details.path("blockReason").isNull()).isTrue();
        assertThat(details.path("lastPrepaymentChange").path("required").asBoolean())
                .isTrue();
        assertThat(details.path("lastPrepaymentChange").path("comment").asString())
                .isEqualTo("Поздняя отмена");
    }

    @Test
    void staffSeesNoteAndBlockReason() {
        var target = createClient(uniqueLastName());
        clientService.updateMasterNote(target.id(), "Любит френч", developerActor());
        clientService.block(target.id(), "Не пришла трижды", developerActor());

        var details = details(signInMaster(), target.id());

        assertThat(details.path("client").path("blocked").asBoolean()).isTrue();
        assertThat(details.path("masterNote").asString()).isEqualTo("Любит френч");
        assertThat(details.path("blockReason").asString()).isEqualTo("Не пришла трижды");
        assertThat(details.path("lastPrepaymentChange").isNull()).isTrue();
    }

    @Test
    void clientUpdatesOwnCard() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        var lastName = uniqueLastName();

        as(customer, csrf(client.patch().uri("/api/clients/{id}", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateClient("Мария", lastName, null, null, null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.firstName")
                .isEqualTo("Мария")
                .jsonPath("$.lastName")
                .isEqualTo(lastName);
    }

    @Test
    void clientCannotReadForeignCard() {
        var foreign = createClient(uniqueLastName());

        as(signInClient(), client.get().uri("/api/clients/{id}", foreign.id()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void staffChangesPhoneWrittenInAnyForm() {
        var target = createClient(uniqueLastName());
        var phone = uniquePhone();

        changePhone(signInMaster(), target.id(), "8" + phone.substring(2))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.phone")
                .isEqualTo(phone);
    }

    @Test
    void clientCannotChangeOwnPhone() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();

        changePhone(customer, clientId, uniquePhone())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void staffSetsAndClearsContactPhone() {
        var master = signInMaster();
        var target = createClient(uniqueLastName());
        var phone = uniquePhone();

        putContactPhone(master, target.id(), phone)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.contactPhone")
                .isEqualTo(phone);

        putContactPhone(master, target.id(), null).exchange().expectStatus().isOk();
        assertThat(clientQueries.byId(target.id()).orElseThrow().contactPhone()).isNull();
    }

    @Test
    void staffReplacesMasterNote() {
        var target = createClient(uniqueLastName());

        as(signInMaster(), csrf(client.put().uri("/api/clients/{id}/note", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("note", "Аллергия на гель"))
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(clientQueries.masterNote(target.id(), developerActor())).contains("Аллергия на гель");
    }

    @Test
    void clientRecordsAndReadsOwnConsents() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        var offer = documents.current(DocumentKind.OFFER).version();

        as(customer, csrf(client.post().uri("/api/clients/{id}/consents", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RecordConsent(ConsentKind.OFFER, offer, ConsentSource.SITE_CHECKBOX))
                .exchange()
                .expectStatus()
                .isNoContent();

        as(customer, client.get().uri("/api/clients/{id}/consents", clientId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].kind")
                .isEqualTo("OFFER")
                .jsonPath("$[0].documentVersion")
                .isEqualTo(offer)
                .jsonPath("$[0].source")
                .isEqualTo("SITE_CHECKBOX");
    }

    @Test
    void staffSetsPrepaymentFlagAndSeesItInHistory() {
        var master = signInMaster();
        var target = createClient(uniqueLastName());

        as(master, csrf(client.put().uri("/api/clients/{id}/prepayment-required", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("required", true, "comment", "Две поздние отмены"))
                .exchange()
                .expectStatus()
                .isNoContent();

        as(master, client.get().uri("/api/clients/{id}/prepayment-history", target.id()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].required")
                .isEqualTo(true)
                .jsonPath("$[0].reason")
                .isEqualTo("MASTER_SET")
                .jsonPath("$[0].comment")
                .isEqualTo("Две поздние отмены");
        assertThat(details(master, target.id())
                        .path("client")
                        .path("prepaymentRequired")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void staffBlocksAndUnblocks() {
        var master = signInMaster();
        var target = createClient(uniqueLastName());

        block(master, target.id(), "Грубила")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.blocked")
                .isEqualTo(true);
        assertThat(clientQueries.blockReason(target.id(), developerActor())).contains("Грубила");

        as(master, csrf(client.post().uri("/api/clients/{id}/unblock", target.id())))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.blocked")
                .isEqualTo(false);
    }

    @Test
    void clientCannotBlock() {
        var target = createClient(uniqueLastName());

        block(signInClient(), target.id(), "Чужая карточка")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
        assertThat(clientQueries.byId(target.id()).orElseThrow().blocked()).isFalse();
    }

    @Test
    void blockReasonLongerThan500IsRejected() {
        var target = createClient(uniqueLastName());

        block(signInMaster(), target.id(), "x".repeat(501))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("reason");
        assertThat(clientQueries.byId(target.id()).orElseThrow().blocked()).isFalse();
    }

    @Test
    void mergeKeepsCardInPathAndRemovesSource() {
        var survivor = createClient(uniqueLastName());
        var removed = createClient(uniqueLastName());

        merge(signInMaster(), survivor.id(), removed.id())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.survivorId")
                .isEqualTo(survivor.id().toString())
                .jsonPath("$.removedId")
                .isEqualTo(removed.id().toString());

        assertThat(clientQueries.byId(removed.id())).isEmpty();
    }

    @Test
    void clientCannotMerge() {
        var customer = signInClient();
        var other = createClient(uniqueLastName());

        merge(customer, actor(customer).requireClientId(), other.id())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
        assertThat(clientQueries.byId(other.id())).isPresent();
    }

    @Test
    void bookingsAreSplitIntoActiveAndHistory() {
        var master = signInMaster();
        var target = createClient(uniqueLastName());
        var booking = book(target.id());

        assertThat(bookingIds(master, target.id(), "active")).containsExactly(booking.id());
        assertThat(bookingIds(master, target.id(), "history")).isEmpty();

        bookings.cancel(booking.id(), developerActor());

        assertThat(bookingIds(master, target.id(), "active")).isEmpty();
        assertThat(bookingIds(master, target.id(), "history")).containsExactly(booking.id());
    }

    @Test
    void takenPhoneConflicts() {
        var taken = createClient(uniqueLastName());

        as(signInMaster(), csrf(client.post().uri("/api/clients")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateClient("Анна", uniqueLastName(), null, taken.phone(), null, null))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("clients.phone_taken");
    }

    private JsonNode details(Session session, UUID clientId) {
        return json(as(session, client.get().uri("/api/clients/{id}", clientId)));
    }

    private List<UUID> searchIds(Session session, String query) {
        return ids(json(as(session, client.get().uri("/api/clients?query={query}", query)))
                .path("items"));
    }

    private List<UUID> bookingIds(Session session, UUID clientId, String scope) {
        return ids(json(as(session, client.get().uri("/api/clients/{id}/bookings?scope={scope}", clientId, scope))));
    }

    private RequestHeadersSpec<?> changePhone(Session session, UUID clientId, String phone) {
        return as(session, csrf(client.post().uri("/api/clients/{id}/phone", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone));
    }

    private RequestHeadersSpec<?> putContactPhone(Session session, UUID clientId, @Nullable String phone) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("phone", phone);
        return as(session, csrf(client.put().uri("/api/clients/{id}/contact-phone", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> block(Session session, UUID clientId, String reason) {
        return as(session, csrf(client.post().uri("/api/clients/{id}/block", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason));
    }

    private RequestHeadersSpec<?> merge(Session session, UUID targetId, UUID sourceId) {
        return as(session, csrf(client.post().uri("/api/clients/{id}/merge", targetId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sourceId", sourceId));
    }

    /** Запись клиента на свободный слот нового дня, её ставит разработчик. */
    private BookingView book(UUID clientId) {
        var developer = developerActor();
        var service = mainService();
        var slot = schedule.addSlot(freshDay(), NOON, developer);
        return bookings.createByMaster(
                new CreateBooking(clientId, slot.id(), service.id(), List.of(), null), false, developer);
    }
}
