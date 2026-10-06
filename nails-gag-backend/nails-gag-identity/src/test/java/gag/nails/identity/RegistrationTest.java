package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.model.ClientSource;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.ConsentView;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

@ModuleTest
class RegistrationTest extends IdentityTestSupport {

    @Test
    void registrationCreatesAccountCardConsentAndSession() {
        var required = registrationRequired(new FakeProfile("u1", "+79211234567", "ann@example.com", "Аня", null));

        var signedIn = loginFlow.completeRegistration(
                required.ticket(), new CompleteRegistration("Анна", "8 921 123-45-67", POLICY_VERSION), CLIENT);

        var authenticated = sessions.authenticate(signedIn.sessionToken()).orElseThrow();
        assertThat(authenticated.actor().accountId()).isEqualTo(signedIn.accountId());
        assertThat(authenticated.actor().isStaff()).isFalse();
        var clientId = authenticated.actor().requireClientId();
        assertThat(authenticated.account().clientId()).isEqualTo(clientId);
        assertThat(authenticated.account().displayName()).isEqualTo("Аня");
        assertThat(authenticated.account().createdAt()).isEqualTo(TestTimeConfiguration.START);

        var card = clientQueries.get(clientId, master);
        assertThat(card.firstName()).isEqualTo("Анна");
        assertThat(card.phone()).isEqualTo("+79211234567");
        assertThat(card.contactPhone()).isNull();
        assertThat(card.email()).isEqualTo("ann@example.com");
        assertThat(card.source()).isEqualTo(ClientSource.SELF_REGISTERED);
        assertThat(card.accountId()).isEqualTo(signedIn.accountId());
        assertThat(clientQueries.consents(clientId, master))
                .containsExactly(new ConsentView(
                        ConsentKind.PERSONAL_DATA,
                        POLICY_VERSION,
                        TestTimeConfiguration.START,
                        ConsentSource.SITE_CHECKBOX));
        assertThat(clientQueries.hasCurrentConsents(clientId, Set.of(ConsentKind.PERSONAL_DATA)))
                .isTrue();
        assertThat(loginJournal()).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(LoginOutcome.REGISTERED);
            assertThat(event.accountId()).isEqualTo(signedIn.accountId());
        });
    }

    @Test
    void registrationWithUnverifiedPhoneStoresItAsContactPhone() {
        var signedIn = signInFirstTime(new FakeProfile("u1", null, null, "Аня", null), "8 921 123-45-67");

        var card = clientQueries.get(clientId(signedIn), master);
        assertThat(card.phone()).isNull();
        assertThat(card.contactPhone()).isEqualTo("+79211234567");

        // Введённый номер, отличный от подтверждённого, тоже становится номером для связи.
        var other = signInFirstTime(new FakeProfile("u2", "+79215550000", null, "Оля", null), "+79215550001");
        var otherCard = clientQueries.get(clientId(other), master);
        assertThat(otherCard.phone()).isEqualTo("+79215550000");
        assertThat(otherCard.contactPhone()).isEqualTo("+79215550001");
    }

    @Test
    void registrationWithOutdatedPolicyVersionIsRejected() {
        var required = registrationRequired(profile("u1", "+79211234567"));

        assertThatThrownBy(() -> loginFlow.completeRegistration(
                        required.ticket(), new CompleteRegistration("Анна", "+79211234567", "2020-01-01"), CLIENT))
                .isInstanceOf(ConsentVersionOutdatedException.class)
                .satisfies(e -> assertThat(((ConsentVersionOutdatedException) e).params())
                        .containsEntry("currentVersion", POLICY_VERSION));

        assertThat(complete(profile("u1", "+79211234567"))).isInstanceOf(RegistrationRequired.class);
        assertThat(clientQueries.byPhone("+79211234567")).isEmpty();
    }

    @Test
    void registrationCommandIsValidated() {
        var required = registrationRequired(profile("u1", "+79211234567"));

        assertThatThrownBy(() -> loginFlow.completeRegistration(
                        required.ticket(), new CompleteRegistration(" ", "+79211234567", POLICY_VERSION), CLIENT))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> loginFlow.completeRegistration(
                        required.ticket(), new CompleteRegistration("Анна", "not a phone", POLICY_VERSION), CLIENT))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void ticketIsSingleUse() {
        var required = registrationRequired(profile("u1", "+79211234567"));
        var command = new CompleteRegistration("Анна", "+79211234567", POLICY_VERSION);
        loginFlow.completeRegistration(required.ticket(), command, CLIENT);

        assertThatThrownBy(() -> loginFlow.completeRegistration(required.ticket(), command, CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
        assertThatThrownBy(() -> loginFlow.completeRegistration("unknown", command, CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
    }

    @Test
    void ticketExpiresAfter30Minutes() {
        var required = registrationRequired(profile("u1", "+79211234567"));
        var command = new CompleteRegistration("Анна", "+79211234567", POLICY_VERSION);
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> loginFlow.completeRegistration(required.ticket(), command, CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
    }

    @Test
    void registrationAttachesToMasterCardByPhone() {
        var card = clients.create(
                new CreateClient("Мария", "Иванова", null, "+79211234567", "m@example.com", null), master);
        clients.updateMasterNote(card.id(), "любит квадрат", master);

        var signedIn = signInFirstTime(new FakeProfile("u1", "+79211234567", null, "Маша", null));

        assertThat(clientId(signedIn)).isEqualTo(card.id());
        var view = clientQueries.get(card.id(), master);
        assertThat(view.firstName()).isEqualTo("Мария");
        assertThat(view.lastName()).isEqualTo("Иванова");
        assertThat(view.email()).isEqualTo("m@example.com");
        assertThat(view.phone()).isEqualTo("+79211234567");
        assertThat(view.contactPhone()).isNull();
        assertThat(view.source()).isEqualTo(ClientSource.ADDED_BY_MASTER);
        assertThat(view.accountId()).isEqualTo(signedIn.accountId());
        assertThat(clientQueries.masterNote(card.id(), master)).contains("любит квадрат");
        assertThat(clientQueries.hasCurrentConsents(card.id(), Set.of(ConsentKind.PERSONAL_DATA)))
                .isTrue();
    }

    @Test
    void registrationAttachesToMasterCardByEmailAndConfirmsPhone() {
        var card = clients.create(new CreateClient("Мария", null, null, null, "M@example.com", null), master);

        var signedIn = signInFirstTime(new FakeProfile("u1", "+79211234567", "m@example.com", "Маша", null));

        assertThat(clientId(signedIn)).isEqualTo(card.id());
        var view = clientQueries.get(card.id(), master);
        assertThat(view.phone()).isEqualTo("+79211234567");
        assertThat(view.firstName()).isEqualTo("Мария");
    }

    @Test
    void parallelRegistrationOfTheSameSubjectEndsAsSignIn() {
        var first = registrationRequired(profile("u1", "+79211234567"));
        var second = registrationRequired(profile("u1", "+79211234567"));
        var command = new CompleteRegistration("Анна", "+79211234567", POLICY_VERSION);

        var registered = loginFlow.completeRegistration(first.ticket(), command, CLIENT);
        var signedIn = loginFlow.completeRegistration(second.ticket(), command, CLIENT);

        assertThat(signedIn.accountId()).isEqualTo(registered.accountId());
        assertThat(signedIn.sessionToken()).isNotEqualTo(registered.sessionToken());
        assertThat(me(signedIn).loginMethods()).hasSize(1);
        assertThat(loginJournal())
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.SIGNED_IN, LoginOutcome.REGISTERED);
    }
}
