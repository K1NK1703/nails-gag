package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ConsentsTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    DocumentVersionService documents;

    private final Actor master = Actor.master();
    private ClientView maria;
    private Actor mariaActor;

    @BeforeEach
    void createClient() {
        maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        mariaActor = Actor.client(maria.id());
    }

    @Test
    void newClientHasNoCurrentConsents() {
        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)))
                .isFalse();
    }

    @Test
    void clientRecordsSiteCheckboxForCurrentVersions() {
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.SITE_CHECKBOX),
                mariaActor);
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.OFFER, "2026-09-25", ConsentSource.SITE_CHECKBOX),
                mariaActor);

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)))
                .isTrue();
        assertThat(queries.consents(maria.id(), mariaActor))
                .extracting(ConsentView::kind)
                .containsExactlyInAnyOrder(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER);
        assertThat(queries.consents(maria.id(), mariaActor))
                .allSatisfy(c -> assertThat(c.givenAt()).isEqualTo(TestTimeConfiguration.START));
    }

    @Test
    void outdatedVersionIsRejectedWithCurrentOne() {
        assertThatThrownBy(() -> clients.recordConsent(
                        maria.id(),
                        new RecordConsent(ConsentKind.OFFER, "2026-01-01", ConsentSource.SITE_CHECKBOX),
                        mariaActor))
                .isInstanceOf(ConsentVersionOutdatedException.class)
                .satisfies(e -> assertThat(((ConsentVersionOutdatedException) e).params())
                        .containsEntry("currentVersion", "2026-09-25"));
    }

    @Test
    void newDocumentVersionInvalidatesOldConsent() {
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.OFFER, "2026-09-25", ConsentSource.SITE_CHECKBOX),
                mariaActor);
        documents.addVersion(
                new AddDocumentVersion(DocumentKind.OFFER, "2026-10-01", LocalDate.of(2026, 10, 1)), Actor.developer());

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.OFFER)))
                .isFalse();
    }

    @Test
    void masterRecordsConsentObtainedPersonally() {
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.ENTERED_BY_MASTER),
                master);

        assertThat(queries.consents(maria.id(), master))
                .singleElement()
                .satisfies(c -> assertThat(c.source()).isEqualTo(ConsentSource.ENTERED_BY_MASTER));
    }

    @Test
    void clientCannotRecordMasterSourceOrForOthers() {
        var other = clients.create(new CreateClient("Аня", null, null, null, null, null), master);

        assertThatThrownBy(() -> clients.recordConsent(
                        maria.id(),
                        new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.ENTERED_BY_MASTER),
                        mariaActor))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.recordConsent(
                        other.id(),
                        new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.SITE_CHECKBOX),
                        mariaActor))
                .isInstanceOf(AccessDeniedException.class);
    }
}
