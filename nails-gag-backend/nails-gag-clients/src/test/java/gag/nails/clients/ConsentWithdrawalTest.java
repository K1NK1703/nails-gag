package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.error.ConsentRequiredException;
import gag.nails.clients.event.ConsentGiven;
import gag.nails.clients.event.ConsentWithdrawn;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.ConsentView;
import gag.nails.clients.model.ConsentWithdrawalView;
import gag.nails.clients.model.WithdrawnBy;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class ConsentWithdrawalTest {

    private static final String CURRENT_VERSION = "2026-09-25";

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    MutableClock clock;

    private final Actor master = Actor.master();
    private ClientView maria;
    private Actor mariaActor;

    @BeforeEach
    void createClient() {
        maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        mariaActor = Actor.client(maria.id());
    }

    @Test
    void clientWithdrawsOwnConsent() {
        givePhotoPublication(mariaActor);
        clock.advance(Duration.ofHours(1));

        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PHOTO_PUBLICATION)))
                .isFalse();
        assertThat(queries.consentWithdrawals(maria.id(), mariaActor))
                .containsExactly(new ConsentWithdrawalView(
                        ConsentKind.PHOTO_PUBLICATION,
                        TestTimeConfiguration.START.plus(Duration.ofHours(1)),
                        WithdrawnBy.CLIENT));
    }

    @Test
    void staffWithdrawsConsent() {
        givePhotoPublication(mariaActor);

        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, master);

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PHOTO_PUBLICATION)))
                .isFalse();
        assertThat(queries.consentWithdrawals(maria.id(), master))
                .containsExactly(new ConsentWithdrawalView(
                        ConsentKind.PHOTO_PUBLICATION, TestTimeConfiguration.START, WithdrawnBy.STAFF));
    }

    @Test
    void foreignClientCannotWithdraw() {
        givePhotoPublication(mariaActor);
        var anna = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        var annaActor = Actor.client(anna.id());

        assertThatThrownBy(() -> clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, annaActor))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.consentWithdrawals(maria.id(), annaActor))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PHOTO_PUBLICATION)))
                .isTrue();
    }

    @Test
    void personalDataCannotBeWithdrawn() {
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, CURRENT_VERSION, ConsentSource.SITE_CHECKBOX),
                mariaActor);
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.OFFER, CURRENT_VERSION, ConsentSource.SITE_CHECKBOX),
                mariaActor);

        assertThatThrownBy(() -> clients.withdrawConsent(maria.id(), ConsentKind.PERSONAL_DATA, mariaActor))
                .isInstanceOfSatisfying(ConsentRequiredException.class, e -> {
                    assertThat(e.code()).isEqualTo("clients.consent_required");
                    assertThat(e.params()).containsEntry("kind", ConsentKind.PERSONAL_DATA);
                });
        assertThatThrownBy(() -> clients.withdrawConsent(maria.id(), ConsentKind.OFFER, master))
                .isInstanceOfSatisfying(
                        ConsentRequiredException.class,
                        e -> assertThat(e.params()).containsEntry("kind", ConsentKind.OFFER));
        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)))
                .isTrue();
        assertThat(queries.consentWithdrawals(maria.id(), master)).isEmpty();
    }

    @Test
    void withdrawnConsentIsNotCurrent() {
        givePhotoPublication(mariaActor);
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.REVIEW_PUBLICATION, CURRENT_VERSION, ConsentSource.SITE_CHECKBOX),
                mariaActor);

        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);
        clients.withdrawConsent(maria.id(), ConsentKind.REVIEW_PUBLICATION, mariaActor);

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PHOTO_PUBLICATION)))
                .isFalse();
        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.REVIEW_PUBLICATION)))
                .isFalse();

        givePhotoPublication(mariaActor);

        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PHOTO_PUBLICATION)))
                .isTrue();
    }

    @Test
    void withdrawalIsVisibleInHistory() {
        givePhotoPublication(mariaActor);
        clock.advance(Duration.ofMinutes(5));
        givePhotoPublication(mariaActor);
        clock.advance(Duration.ofMinutes(5));
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);
        clock.advance(Duration.ofMinutes(5));
        givePhotoPublication(mariaActor);
        clock.advance(Duration.ofMinutes(5));
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, master);

        assertThat(queries.consentWithdrawals(maria.id(), mariaActor))
                .containsExactly(
                        new ConsentWithdrawalView(
                                ConsentKind.PHOTO_PUBLICATION,
                                TestTimeConfiguration.START.plus(Duration.ofMinutes(20)),
                                WithdrawnBy.STAFF),
                        new ConsentWithdrawalView(
                                ConsentKind.PHOTO_PUBLICATION,
                                TestTimeConfiguration.START.plus(Duration.ofMinutes(10)),
                                WithdrawnBy.CLIENT));
        assertThat(queries.consents(maria.id(), mariaActor))
                .extracting(ConsentView::givenAt)
                .containsExactly(
                        TestTimeConfiguration.START.plus(Duration.ofMinutes(15)),
                        TestTimeConfiguration.START.plus(Duration.ofMinutes(5)),
                        TestTimeConfiguration.START);
    }

    @Test
    void eventsArePublished(PublishedEvents events) {
        givePhotoPublication(mariaActor);
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);
        clock.advance(Duration.ofMinutes(1));
        givePhotoPublication(mariaActor);

        assertThat(events.ofType(ConsentGiven.class).matching(e -> e.clientId().equals(maria.id())))
                .containsExactly(
                        new ConsentGiven(maria.id(), ConsentKind.PHOTO_PUBLICATION, TestTimeConfiguration.START),
                        new ConsentGiven(
                                maria.id(),
                                ConsentKind.PHOTO_PUBLICATION,
                                TestTimeConfiguration.START.plus(Duration.ofMinutes(1))));
        assertThat(events.ofType(ConsentWithdrawn.class)
                        .matching(e -> e.clientId().equals(maria.id())))
                .containsExactly(
                        new ConsentWithdrawn(maria.id(), ConsentKind.PHOTO_PUBLICATION, TestTimeConfiguration.START));
    }

    @Test
    void withdrawWithoutConsentChangesNothing(PublishedEvents events) {
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);

        assertThat(events.ofType(ConsentWithdrawn.class)
                        .matching(e -> e.clientId().equals(maria.id())))
                .isEmpty();
        assertThat(queries.consentWithdrawals(maria.id(), master)).isEmpty();

        givePhotoPublication(mariaActor);
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, mariaActor);
        clients.withdrawConsent(maria.id(), ConsentKind.PHOTO_PUBLICATION, master);

        assertThat(events.ofType(ConsentWithdrawn.class)
                        .matching(e -> e.clientId().equals(maria.id())))
                .hasSize(1);
        assertThat(queries.consentWithdrawals(maria.id(), master)).hasSize(1);
    }

    private void givePhotoPublication(Actor actor) {
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.PHOTO_PUBLICATION, CURRENT_VERSION, ConsentSource.SITE_CHECKBOX),
                actor);
    }
}
