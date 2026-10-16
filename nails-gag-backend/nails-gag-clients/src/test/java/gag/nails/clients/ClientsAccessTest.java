package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.AddDocumentVersion;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ClientsAccessTest {

    private static final String CURRENT_VERSION = "2026-09-25";

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    DocumentVersionService documents;

    @Autowired
    MutableClock clock;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor master = Actor.master();
    private ClientView maria;
    private ClientView anna;

    @BeforeEach
    void createClients() {
        maria = clients.create(new CreateClient("Мария", null, null, "+7 921 123-45-67", null, null), master);
        anna = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
    }

    @Test
    void readerSeesClientsButChangesNothing() {
        var reader = Actor.of(null, null, Map.of(Section.CLIENTS, AccessLevel.READ));

        assertThat(queries.get(maria.id(), reader).firstName()).isEqualTo("Мария");
        assertThat(queries.search("Мар", reader)).extracting(ClientView::id).containsExactly(maria.id());
        assertThat(queries.masterNote(maria.id(), reader)).isEmpty();
        assertThat(queries.consents(maria.id(), reader)).isEmpty();
        assertThat(queries.prepaymentHistory(maria.id(), reader)).isEmpty();

        assertThatThrownBy(() -> clients.create(new CreateClient("Оля", null, null, null, null, null), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.update(maria.id(), rename("Маша"), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.changePhone(maria.id(), "+79211234568", reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.updateMasterNote(maria.id(), "заметка", reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.setPrepaymentRequired(maria.id(), true, null, reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.merge(anna.id(), maria.id(), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.recordConsent(maria.id(), consent(ConsentSource.ENTERED_BY_MASTER), reader))
                .isInstanceOf(AccessDeniedException.class);

        entityManager.flush();
        entityManager.clear();
        var unchanged = queries.get(maria.id(), reader);
        assertThat(unchanged.firstName()).isEqualTo("Мария");
        assertThat(unchanged.phone()).isEqualTo("+79211234567");
        assertThat(unchanged.prepaymentRequired()).isFalse();
        assertThat(queries.masterNote(maria.id(), reader)).isEmpty();
        assertThat(queries.consents(maria.id(), reader)).isEmpty();
        assertThat(queries.prepaymentHistory(maria.id(), reader)).isEmpty();
        assertThat(queries.search("", reader))
                .extracting(ClientView::id)
                .containsExactlyInAnyOrder(maria.id(), anna.id());
    }

    @Test
    void writerDoesEverythingInClients() {
        var writer = Actor.of(null, null, Map.of(Section.CLIENTS, AccessLevel.WRITE));

        assertThat(queries.get(maria.id(), writer).firstName()).isEqualTo("Мария");
        assertThat(queries.search("", writer)).hasSize(2);
        assertThat(queries.masterNote(maria.id(), writer)).isEmpty();
        assertThat(queries.consents(maria.id(), writer)).isEmpty();
        assertThat(queries.prepaymentHistory(maria.id(), writer)).isEmpty();

        assertThatCode(() -> {
                    clients.create(new CreateClient("Оля", null, null, null, null, null), writer);
                    clients.update(maria.id(), rename("Маша"), writer);
                    clients.changePhone(maria.id(), "+79211234568", writer);
                    clients.updateMasterNote(maria.id(), "заметка", writer);
                    clients.setPrepaymentRequired(maria.id(), true, null, writer);
                    clients.recordConsent(maria.id(), consent(ConsentSource.ENTERED_BY_MASTER), writer);
                    clients.merge(anna.id(), maria.id(), writer);
                })
                .doesNotThrowAnyException();
    }

    @Test
    void clientReadsAndUpdatesOnlySelf() {
        var me = Actor.client(maria.id());

        assertThat(queries.get(maria.id(), me).firstName()).isEqualTo("Мария");
        assertThat(queries.consents(maria.id(), me)).isEmpty();
        assertThatCode(() -> {
                    clients.update(maria.id(), rename("Маша"), me);
                    clients.recordConsent(maria.id(), consent(ConsentSource.SITE_CHECKBOX), me);
                })
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> queries.get(anna.id(), me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.consents(anna.id(), me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.update(anna.id(), rename("Анна"), me))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.recordConsent(anna.id(), consent(ConsentSource.SITE_CHECKBOX), me))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> queries.search("", me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.masterNote(maria.id(), me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.prepaymentHistory(maria.id(), me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.recordConsent(maria.id(), consent(ConsentSource.ENTERED_BY_MASTER), me))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void bookingsWriterHasNoAccessToClients() {
        var bookingsWriter = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.WRITE));

        assertThatThrownBy(() -> queries.search("", bookingsWriter)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void documentsNeedTheirOwnSection() {
        var clientsWriter = Actor.of(null, null, Map.of(Section.CLIENTS, AccessLevel.WRITE));

        assertThatThrownBy(() -> documents.addVersion(version("2026-10-02"), master))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> documents.addVersion(version("2026-10-02"), clientsWriter))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(documents
                        .addVersion(version("2026-10-02"), Actor.developer())
                        .version())
                .isEqualTo("2026-10-02");
    }

    @Test
    void lastPrepaymentChangeIsTheNewestOne() {
        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null));
        clock.advance(Duration.ofMinutes(1));
        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(false, PrepaymentFlagReason.NO_SHOW_CORRECTED, null, null));

        assertThat(queries.lastPrepaymentChange(maria.id())).hasValueSatisfying(change -> {
            assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.NO_SHOW_CORRECTED);
            assertThat(change.required()).isFalse();
        });
        assertThat(queries.lastPrepaymentChange(anna.id())).isEmpty();
    }

    private static UpdateClient rename(String firstName) {
        return new UpdateClient(firstName, null, null, null, null);
    }

    private static RecordConsent consent(ConsentSource source) {
        return new RecordConsent(ConsentKind.PERSONAL_DATA, CURRENT_VERSION, source);
    }

    private static AddDocumentVersion version(String version) {
        return new AddDocumentVersion(
                DocumentKind.OFFER, version, LocalDate.parse(version), "Текст редакции " + version);
    }
}
