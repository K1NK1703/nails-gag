package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class DocumentVersionServiceTest {

    @Autowired
    DocumentVersionService documents;

    @Autowired
    MutableClock clock;

    @Test
    void seededVersionsAreCurrent() {
        assertThat(documents.current(DocumentKind.POLICY).version()).isEqualTo("2026-09-25");
        assertThat(documents.current(DocumentKind.OFFER).version()).isEqualTo("2026-09-25");
    }

    @Test
    void futureVersionBecomesCurrentOnItsEffectiveDate() {
        // часы теста стоят на 1 октября 2026
        documents.addVersion(DocumentKind.OFFER, "2026-10-02", LocalDate.of(2026, 10, 2), Actor.master());
        assertThat(documents.current(DocumentKind.OFFER).version()).isEqualTo("2026-09-25");

        clock.advance(Duration.ofDays(1));
        assertThat(documents.current(DocumentKind.OFFER).version()).isEqualTo("2026-10-02");
        assertThat(documents.current(DocumentKind.POLICY).version()).isEqualTo("2026-09-25");
    }

    @Test
    void historyListsNewestFirst() {
        documents.addVersion(DocumentKind.POLICY, "2026-10-01", LocalDate.of(2026, 10, 1), Actor.master());

        assertThat(documents.history(DocumentKind.POLICY))
                .extracting(DocumentVersionView::version)
                .containsExactly("2026-10-01", "2026-09-25");
    }

    @Test
    void duplicateVersionIsRejected() {
        assertThatThrownBy(() -> documents.addVersion(
                        DocumentKind.POLICY, "2026-09-25", LocalDate.of(2026, 10, 1), Actor.master()))
                .isInstanceOf(DocumentVersionExistsException.class);
    }

    @Test
    void onlyMasterAddsVersions() {
        assertThatThrownBy(() -> documents.addVersion(
                        DocumentKind.POLICY, "x", LocalDate.of(2026, 10, 1), Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
