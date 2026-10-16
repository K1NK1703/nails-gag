package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.AddDocumentVersion;
import gag.nails.clients.error.DocumentVersionDateTakenException;
import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.clients.error.DocumentVersionNotFoundException;
import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionText;
import gag.nails.clients.model.DocumentVersionView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
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
        documents.addVersion(
                new AddDocumentVersion(
                        DocumentKind.OFFER, "2026-10-02", LocalDate.of(2026, 10, 2), "Текст редакции 2026-10-02"),
                Actor.developer());
        assertThat(documents.current(DocumentKind.OFFER).version()).isEqualTo("2026-09-25");

        clock.advance(Duration.ofDays(1));
        assertThat(documents.current(DocumentKind.OFFER).version()).isEqualTo("2026-10-02");
        assertThat(documents.current(DocumentKind.POLICY).version()).isEqualTo("2026-09-25");
    }

    @Test
    void historyListsNewestFirst() {
        documents.addVersion(
                new AddDocumentVersion(
                        DocumentKind.POLICY, "2026-10-01", LocalDate.of(2026, 10, 1), "Текст редакции 2026-10-01"),
                Actor.developer());

        assertThat(documents.history(DocumentKind.POLICY))
                .extracting(DocumentVersionView::version)
                .containsExactly("2026-10-01", "2026-09-25");
    }

    @Test
    void duplicateVersionIsRejected() {
        assertThatThrownBy(() -> documents.addVersion(
                        new AddDocumentVersion(
                                DocumentKind.POLICY,
                                "2026-09-25",
                                LocalDate.of(2026, 10, 1),
                                "Текст редакции 2026-09-25"),
                        Actor.developer()))
                .isInstanceOf(DocumentVersionExistsException.class);
    }

    @Test
    void onlyMasterAddsVersions() {
        assertThatThrownBy(() -> documents.addVersion(
                        new AddDocumentVersion(DocumentKind.POLICY, "x", LocalDate.of(2026, 10, 1), "Текст редакции x"),
                        Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void secondVersionOnTheSameDateIsRejected() {
        assertThatThrownBy(() -> documents.addVersion(
                        new AddDocumentVersion(
                                DocumentKind.POLICY,
                                "2026-09-25-b",
                                LocalDate.of(2026, 9, 25),
                                "Текст редакции 2026-09-25-b"),
                        Actor.developer()))
                .isInstanceOf(DocumentVersionDateTakenException.class);
    }

    @Test
    void blankVersionIsRejected() {
        assertThatThrownBy(() -> documents.addVersion(
                        new AddDocumentVersion(DocumentKind.POLICY, " ", LocalDate.of(2026, 10, 1), "Текст редакции"),
                        Actor.developer()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "version"));
    }

    @Test
    void versionReturnsStoredText() {
        var added = documents.addVersion(
                new AddDocumentVersion(DocumentKind.POLICY, "2026-10-01", LocalDate.of(2026, 10, 1), "Новая политика"),
                Actor.developer());

        assertThat(documents.version(DocumentKind.POLICY, "2026-10-01", Actor.master()))
                .isEqualTo(new DocumentVersionText(added, "Новая политика"));
    }

    @Test
    void seedVersionsHaveNoText() {
        var seed = documents.version(DocumentKind.POLICY, "2026-09-25", Actor.master());

        assertThat(seed.version()).isEqualTo(documents.current(DocumentKind.POLICY));
        assertThat(seed.text()).isNull();
    }

    @Test
    void unknownVersionIsNotFound() {
        assertThatThrownBy(() -> documents.version(DocumentKind.OFFER, "2020-01-01", Actor.master()))
                .isInstanceOf(DocumentVersionNotFoundException.class)
                .satisfies(e -> assertThat(((DocumentVersionNotFoundException) e).params())
                        .containsEntry("kind", DocumentKind.OFFER)
                        .containsEntry("version", "2020-01-01"));
    }

    @Test
    void clientCannotReadVersionText() {
        assertThatThrownBy(() -> documents.version(DocumentKind.POLICY, "2026-09-25", Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void blankTextIsRejected() {
        assertThatThrownBy(() -> documents.addVersion(
                        new AddDocumentVersion(DocumentKind.POLICY, "2026-10-01", LocalDate.of(2026, 10, 1), " "),
                        Actor.developer()))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "text"));
    }
}
