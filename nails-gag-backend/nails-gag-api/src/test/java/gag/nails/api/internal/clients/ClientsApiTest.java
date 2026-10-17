package gag.nails.api.internal.clients;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookingView;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleService;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Клиенты (спецификация API, §7.4): карточки и их журналы — сотруднику по разделу «Клиенты», свои — клиенту; заметка
 * и причина блокировки клиенту не видны. Права проверяет модуль.
 */
@ApiTest
class ClientsApiTest extends ApiTestSupport {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    ClientService clientService;

    @Autowired
    ClientQueries clientQueries;

    @Autowired
    CatalogService catalog;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Test
    void searchIsPagedForStaff() {
        var lastName = uniqueName();
        for (var i = 0; i < 3; i++) {
            createClient(lastName);
        }

        as(signInMaster(), client.get().uri("/api/clients?query={query}&size=2", lastName))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(2)
                .jsonPath("$.items[0].lastName")
                .isEqualTo(lastName)
                .jsonPath("$.page")
                .isEqualTo(0)
                .jsonPath("$.size")
                .isEqualTo(2)
                .jsonPath("$.total")
                .isEqualTo(3);
    }

    @Test
    void masterCreatesClient() {
        var phone = uniquePhone();
        var lastName = uniqueName();

        as(
                        signInMaster(),
                        csrf(client.post().uri("/api/clients"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new CreateClient("Анна", lastName, null, phone, null, null)))
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
        clientService.updateMasterNote(clientId, "Только для мастера", developer());
        clientService.setPrepaymentRequired(clientId, true, "Поздняя отмена", developer());

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
        var target = createClient(uniqueName());
        clientService.updateMasterNote(target.id(), "Любит френч", developer());
        clientService.block(target.id(), "Не пришла трижды", developer());

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
        var lastName = uniqueName();

        as(
                        customer,
                        csrf(client.patch().uri("/api/clients/{id}", clientId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new UpdateClient("Мария", lastName, null, null, null)))
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
        var foreign = createClient(uniqueName());

        as(signInClient(), client.get().uri("/api/clients/{id}", foreign.id()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void changePhone() {
        var target = createClient(uniqueName());
        var phone = uniquePhone();
        var written = "8" + phone.substring(2);

        as(signInMaster(), csrf(client.post().uri("/api/clients/{id}/phone", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", written))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.phone")
                .isEqualTo(phone);
    }

    @Test
    void contactPhone() {
        var master = signInMaster();
        var target = createClient(uniqueName());
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
    void note() {
        var target = createClient(uniqueName());

        as(signInMaster(), csrf(client.put().uri("/api/clients/{id}/note", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("note", "Аллергия на гель"))
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(clientQueries.masterNote(target.id(), developer())).contains("Аллергия на гель");
    }

    @Test
    void consentsReadAndRecord() {
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
    void prepaymentFlagAndHistory() {
        var master = signInMaster();
        var target = createClient(uniqueName());

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
    void blockAndUnblock() {
        var master = signInMaster();
        var target = createClient(uniqueName());

        as(master, csrf(client.post().uri("/api/clients/{id}/block", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "Грубила"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.blocked")
                .isEqualTo(true);
        assertThat(clientQueries.blockReason(target.id(), developer())).contains("Грубила");

        as(master, csrf(client.post().uri("/api/clients/{id}/unblock", target.id())))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.blocked")
                .isEqualTo(false);
    }

    @Test
    void merge() {
        var survivor = createClient(uniqueName());
        var removed = createClient(uniqueName());

        as(signInMaster(), csrf(client.post().uri("/api/clients/{id}/merge", survivor.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sourceId", removed.id()))
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
    void bookingsByScope() {
        var master = signInMaster();
        var target = createClient(uniqueName());
        var booking = book(target.id());

        assertThat(bookingIds(master, target.id(), "active")).containsExactly(booking.id());
        assertThat(bookingIds(master, target.id(), "history")).isEmpty();

        bookings.cancel(booking.id(), developer());

        assertThat(bookingIds(master, target.id(), "active")).isEmpty();
        assertThat(bookingIds(master, target.id(), "history")).containsExactly(booking.id());
    }

    @Test
    void phoneTakenConflicts() {
        var taken = createClient(uniqueName());

        as(
                        signInMaster(),
                        csrf(client.post().uri("/api/clients"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new CreateClient("Анна", uniqueName(), null, taken.phone(), null, null)))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("clients.phone_taken");
    }

    @Test
    void blockReasonLongerThan500IsRejected() {
        var target = createClient(uniqueName());

        as(signInMaster(), csrf(client.post().uri("/api/clients/{id}/block", target.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "x".repeat(501)))
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

    private JsonNode details(Session session, UUID clientId) {
        RequestHeadersSpec<?> spec = as(session, client.get().uri("/api/clients/{id}", clientId));
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    private RequestHeadersSpec<?> putContactPhone(Session session, UUID clientId, @Nullable String phone) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("phone", phone);
        return as(session, csrf(client.put().uri("/api/clients/{id}/contact-phone", clientId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private List<UUID> bookingIds(Session session, UUID clientId, String scope) {
        RequestHeadersSpec<?> spec =
                as(session, client.get().uri("/api/clients/{id}/bookings?scope={scope}", clientId, scope));
        var body = Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
        return body.values().stream()
                .map(booking -> UUID.fromString(booking.path("id").asString()))
                .toList();
    }

    /** Запись клиента на свободный слот нового дня, её ставит разработчик. */
    private BookingView book(UUID clientId) {
        var developer = developer();
        var service = catalog.create(
                new CreateService(
                        ServiceKind.MAIN,
                        "Услуга " + UUID.randomUUID(),
                        "Описание",
                        new BigDecimal("1500.00"),
                        60,
                        "Маникюр",
                        false,
                        false),
                developer);
        var slot = schedule.addSlot(freshDay(), NOON, developer);
        return bookings.createByMaster(
                new CreateBooking(clientId, slot.id(), service.id(), List.of(), null), false, developer);
    }

    private ClientView createClient(String lastName) {
        return clientService.create(new CreateClient("Анна", lastName, null, uniquePhone(), null, null), developer());
    }

    private Actor developer() {
        return actor(signInDeveloper());
    }

    /** Фамилия, которой ещё нет в базе: только буквы, чтобы поиск не принял её за телефон. */
    private static String uniqueName() {
        var random = ThreadLocalRandom.current();
        var name = new StringBuilder("Тест");
        for (var i = 0; i < 12; i++) {
            name.append((char) ('a' + random.nextInt(26)));
        }
        return name.toString();
    }
}
