package gag.nails.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class CatalogServiceTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");
    private static final UUID FRENCH = UUID.fromString("019a0c00-0000-7000-8000-000000000002");
    private static final UUID DESIGN = UUID.fromString("019a0c00-0000-7000-8000-000000000003");

    @Autowired
    CatalogService catalog;

    @Autowired
    CatalogQueries queries;

    private final Actor master = Actor.master();

    @Test
    void masterCreatesMainServiceAtTheEndOfPriceList() {
        var created = catalog.create(mainService("Педикюр", 90), master);

        assertThat(created.id()).isNotNull();
        assertThat(created.sortOrder()).isEqualTo(3);
        assertThat(created.active()).isTrue();
        assertThat(queries.all()).extracting(ServiceView::title).endsWith("Педикюр");
    }

    @Test
    void clientCannotManageCatalog() {
        assertThatThrownBy(() -> catalog.create(mainService("Педикюр", 90), Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void mainServiceRequiresDuration() {
        assertThatThrownBy(() -> catalog.create(mainService("Педикюр", null), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).params()).containsEntry("field", "durationMinutes"));
    }

    @Test
    void addonMustNotHaveDuration() {
        var addon = new CreateService(
                ServiceKind.ADDON, "Стразы", "", new BigDecimal("150.00"), 15, "Дизайн", false, false);

        assertThatThrownBy(() -> catalog.create(addon, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).params()).containsEntry("field", "durationMinutes"));
    }

    @Test
    void blankTitleIsRejectedByBeanValidation() {
        assertThatThrownBy(() -> catalog.create(mainService("  ", 60), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "title"));
    }

    @Test
    void updateChangesEverythingButKind() {
        var updated = catalog.update(
                FRENCH,
                new UpdateService(
                        "Френч цветной", "Описание", new BigDecimal("350.00"), null, "Доп. покрытие", true, false),
                master);

        assertThat(updated.kind()).isEqualTo(ServiceKind.ADDON);
        assertThat(updated.title()).isEqualTo("Френч цветной");
        assertThat(updated.price()).isEqualByComparingTo("350.00");
        assertThat(queries.byId(FRENCH).title()).isEqualTo("Френч цветной");
    }

    @Test
    void deactivatedServiceLeavesActiveListAndCanBeActivatedAgain() {
        catalog.deactivate(FRENCH, master);
        assertThat(queries.activeServices()).extracting(ServiceView::id).doesNotContain(FRENCH);

        catalog.activate(FRENCH, master);
        assertThat(queries.activeServices()).extracting(ServiceView::id).contains(FRENCH);
    }

    @Test
    void reorderAssignsPositionsInGivenOrder() {
        catalog.reorder(List.of(DESIGN, FRENCH, MANICURE), master);

        assertThat(queries.all()).extracting(ServiceView::id).containsExactly(DESIGN, FRENCH, MANICURE);
    }

    @Test
    void reorderRejectsUnknownService() {
        assertThatThrownBy(() -> catalog.reorder(List.of(MANICURE, UUID.randomUUID()), master))
                .isInstanceOf(ServiceNotFoundException.class);
    }

    private static CreateService mainService(String title, Integer duration) {
        return new CreateService(
                ServiceKind.MAIN, title, "", new BigDecimal("3000.00"), duration, "Основная услуга", true, false);
    }
}
