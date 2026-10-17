package gag.nails.api.internal.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/**
 * Каталог (спецификация API, §7.3): прайс для лендинга публичен и кэшируется, список услуг — по разделу «Каталог»,
 * ведение каталога — по уровню изменения раздела, его проверяет модуль.
 */
@ApiTest
class ServicesApiTest extends ApiTestSupport {

    private static final ParameterizedTypeReference<List<ServiceView>> SERVICES = new ParameterizedTypeReference<>() {};

    @Autowired
    CatalogQueries catalogQueries;

    @Test
    void priceListIsPublicAndCached() {
        var shown = createService(true);
        var hidden = createService(false);

        var priceList = client.get()
                .uri("/api/price-list")
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
                .expectBody(SERVICES)
                .returnResult()
                .getResponseBody();

        assertThat(ids(priceList)).contains(shown.id()).doesNotContain(hidden.id());
    }

    @Test
    void clientSeesOnlyActiveServices() {
        var active = createService(true);
        var inactive = createInactiveService();

        var services = services(signInClient());

        assertThat(ids(services)).contains(active.id()).doesNotContain(inactive.id());
    }

    @Test
    void masterSeesInactiveToo() {
        var active = createService(true);
        var inactive = createInactiveService();

        var services = services(signInMaster());

        assertThat(ids(services)).contains(active.id(), inactive.id());
    }

    @Test
    void getService() {
        var service = createService(true);

        as(signInClient(), client.get().uri("/api/services/{id}", service.id()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(service.id().toString())
                .jsonPath("$.title")
                .isEqualTo(service.title())
                .jsonPath("$.kind")
                .isEqualTo("MAIN");
    }

    @Test
    void masterCreatesUpdatesDeactivatesActivates() {
        var master = signInMaster();
        var title = uniqueTitle();

        var created = as(master, csrf(client.post().uri("/api/services")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(createCommand(title, true))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(ServiceView.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.title()).isEqualTo(title);
        assertThat(created.active()).isTrue();

        var newTitle = uniqueTitle();
        as(master, csrf(client.patch().uri("/api/services/{id}", created.id())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateService(
                        newTitle, "Новое описание", new BigDecimal("1800.00"), 90, "Маникюр", true, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo(newTitle)
                .jsonPath("$.durationMinutes")
                .isEqualTo(90);

        as(master, csrf(client.post().uri("/api/services/{id}/deactivate", created.id())))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(catalogQueries.get(created.id()).active()).isFalse();

        as(master, csrf(client.post().uri("/api/services/{id}/activate", created.id())))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(catalogQueries.get(created.id()).active()).isTrue();
    }

    @Test
    void reorder() {
        var first = createService(true);
        var second = createService(true);

        as(signInMaster(), csrf(client.put().uri("/api/services/order")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("orderedServiceIds", List.of(second.id(), first.id())))
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(catalogQueries.get(second.id()).sortOrder()).isEqualTo(0);
        assertThat(catalogQueries.get(first.id()).sortOrder()).isEqualTo(1);
    }

    @Test
    void clientCannotCreate() {
        as(signInClient(), csrf(client.post().uri("/api/services")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(createCommand(uniqueTitle(), true))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void unknownServiceIsNotFoundForStaff() {
        as(signInMaster(), client.get().uri("/api/services/{id}", UUID.randomUUID()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("catalog.service_not_found");
    }

    private List<ServiceView> services(Session session) {
        RequestHeadersSpec<?> spec = as(session, client.get().uri("/api/services"));
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isOk()
                .expectBody(SERVICES)
                .returnResult()
                .getResponseBody());
    }

    /** Активная основная услуга, заведённая разработчиком. */
    private ServiceView createService(boolean showOnLanding) {
        return catalog.create(createCommand(uniqueTitle(), showOnLanding), developerActor());
    }

    private ServiceView createInactiveService() {
        var service = createService(false);
        catalog.deactivate(service.id(), developerActor());
        return service;
    }

    private static CreateService createCommand(String title, boolean showOnLanding) {
        return new CreateService(
                ServiceKind.MAIN, title, "Описание", new BigDecimal("1500.00"), 60, "Маникюр", showOnLanding, false);
    }

    private static List<UUID> ids(@Nullable List<ServiceView> services) {
        return Objects.requireNonNull(services).stream().map(ServiceView::id).toList();
    }

    private static String uniqueTitle() {
        return "Услуга " + UUID.randomUUID();
    }
}
