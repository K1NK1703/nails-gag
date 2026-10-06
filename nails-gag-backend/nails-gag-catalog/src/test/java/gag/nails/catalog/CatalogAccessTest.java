package gag.nails.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class CatalogAccessTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");
    private static final UUID FRENCH = UUID.fromString("019a0c00-0000-7000-8000-000000000002");

    @Autowired
    CatalogService catalog;

    @Autowired
    CatalogQueries queries;

    @Test
    void readerReadsButDoesNotWrite() {
        var reader = Actor.of(null, null, Map.of(Section.CATALOG, AccessLevel.READ));

        assertThat(queries.all(reader)).isNotEmpty();

        assertThatThrownBy(() -> catalog.create(pedicure(), reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.update(FRENCH, frenchUpdate(), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.activate(FRENCH, reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.deactivate(FRENCH, reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.reorder(List.of(FRENCH, MANICURE), reader))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void writerReadsAndWrites() {
        var writer = Actor.of(null, null, Map.of(Section.CATALOG, AccessLevel.WRITE));

        assertThat(queries.all(writer)).isNotEmpty();
        assertThatCode(() -> {
                    catalog.create(pedicure(), writer);
                    catalog.update(FRENCH, frenchUpdate(), writer);
                    catalog.deactivate(FRENCH, writer);
                    catalog.activate(FRENCH, writer);
                    catalog.reorder(List.of(FRENCH, MANICURE), writer);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void noAccessMeansNoReading() {
        var nobody = Actor.of(null, null, Map.of());

        assertThatThrownBy(() -> queries.all(nobody)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.all(Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otherSectionDoesNotHelp() {
        var scheduler = Actor.of(null, null, Map.of(Section.SCHEDULE, AccessLevel.WRITE));

        assertThatThrownBy(() -> queries.all(scheduler)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> catalog.create(pedicure(), scheduler)).isInstanceOf(AccessDeniedException.class);
    }

    private static CreateService pedicure() {
        return new CreateService(
                ServiceKind.MAIN, "Педикюр", "", new BigDecimal("2500.00"), 90, "Педикюр", false, false);
    }

    private static UpdateService frenchUpdate() {
        return new UpdateService("Френч цветной", "Описание", new BigDecimal("350.00"), null, "Дизайн", true, false);
    }
}
