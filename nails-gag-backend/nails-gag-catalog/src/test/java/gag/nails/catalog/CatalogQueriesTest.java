package gag.nails.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@ModuleTest
class CatalogQueriesTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");
    private static final UUID FRENCH = UUID.fromString("019a0c00-0000-7000-8000-000000000002");
    private static final Actor MASTER = Actor.master();

    @Autowired
    CatalogQueries queries;

    @Autowired
    JdbcClient jdbc;

    @Test
    void landingPriceListContainsOnlyVisibleActiveServicesInOrder() {
        jdbc.sql("update catalog.service set show_on_landing = false where id = :id")
                .param("id", FRENCH)
                .update();

        var titles = queries.landingPriceList().stream().map(ServiceView::title).toList();

        assertThat(titles).containsExactly("Маникюр", "Втирка / дизайн");
    }

    @Test
    void activeServicesExcludeHiddenOnes() {
        jdbc.sql("update catalog.service set active = false where id = :id")
                .param("id", FRENCH)
                .update();

        assertThat(queries.activeServices()).extracting(ServiceView::title).doesNotContain("Френч");
        assertThat(queries.all(MASTER)).extracting(ServiceView::title).contains("Френч");
    }

    @Test
    void allIsMasterOnly() {
        assertThatThrownBy(() -> queries.all(Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void byIdReturnsFullView() {
        var manicure = queries.get(MANICURE);

        assertThat(manicure.kind()).isEqualTo(ServiceKind.MAIN);
        assertThat(manicure.price()).isEqualByComparingTo(new BigDecimal("2500.00"));
        assertThat(manicure.durationMinutes()).isEqualTo(120);
        assertThat(manicure.highlightOnLanding()).isTrue();
    }

    @Test
    void unknownIdIsReported() {
        var missing = UUID.randomUUID();

        assertThatThrownBy(() -> queries.get(missing))
                .isInstanceOf(ServiceNotFoundException.class)
                .satisfies(
                        e -> assertThat(((ServiceNotFoundException) e).params()).containsEntry("serviceId", missing));
    }

    @Test
    void requireActiveReturnsServicesInRequestedOrder() {
        var services = queries.requireActive(List.of(FRENCH, MANICURE));

        assertThat(services).extracting(ServiceView::id).containsExactly(FRENCH, MANICURE);
    }

    @Test
    void requireActiveRejectsHiddenService() {
        jdbc.sql("update catalog.service set active = false where id = :id")
                .param("id", FRENCH)
                .update();

        assertThatThrownBy(() -> queries.requireActive(List.of(MANICURE, FRENCH)))
                .isInstanceOf(ServiceInactiveException.class)
                .satisfies(
                        e -> assertThat(((ServiceInactiveException) e).params()).containsEntry("title", "Френч"));
    }

    @Test
    void requireActiveRejectsUnknownService() {
        assertThatThrownBy(() -> queries.requireActive(List.of(MANICURE, UUID.randomUUID())))
                .isInstanceOf(ServiceNotFoundException.class);
    }
}
