package gag.nails.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.clients.ClientService;
import gag.nails.common.actor.Actor;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.ClientInfo;
import gag.nails.identity.CompleteRegistration;
import gag.nails.identity.LoginEventView;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.LoginOutcome;
import gag.nails.identity.LoginPurpose;
import gag.nails.identity.Provider;
import gag.nails.identity.RegistrationRequired;
import gag.nails.identity.Sessions;
import gag.nails.identity.SignedIn;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Единственный тест модуля мимо публичного API: окно гонки «карточку заблокировали между проверкой и
 * фиксацией сессии» снаружи детерминированно не воспроизвести — публичный {@code complete} проверяет
 * карточку сам и до блокировки не доходит, а слушатель {@code ClientBlocked} закрывает уже
 * зафиксированные сессии. Поэтому сессия открывается напрямую {@link SessionOpener} у уже заблокированной
 * карточки, как если бы вход зафиксировался за мгновение до блокировки, и проверяется
 * {@link LoginWriter#rejectIfBlocked}.
 */
@ModuleTest
class LoginWriterTest {

    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");

    @Autowired
    LoginWriter writer;

    @Autowired
    SessionOpener sessionOpener;

    @Autowired
    LoginMethodRepository loginMethods;

    @Autowired
    LoginFlow loginFlow;

    @Autowired
    Sessions sessions;

    @Autowired
    AccessAdministration access;

    @Autowired
    FakeProvider fake;

    @Autowired
    ClientService clients;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void sessionOpenedForCardBlockedMeanwhileIsClosedAndJournaled() {
        var profile = new FakeProfile("u1", "+79211234567", null, "Анна", null);
        var registered = signInFirstTime(profile);
        var clientId = sessions.authenticate(registered.sessionToken())
                .orElseThrow()
                .actor()
                .requireClientId();
        clients.block(clientId, "грубит", Actor.master());
        var method = loginMethods.findByProviderAndSubject(Provider.FAKE, "u1").orElseThrow();
        var raced = new SignedIn(sessionOpener.open(registered.accountId(), method.id()), registered.accountId());
        assertThat(sessionsOf(registered.accountId())).isEqualTo(1);

        assertThatThrownBy(() -> writer.rejectIfBlocked(raced, Provider.FAKE, CLIENT))
                .isInstanceOf(AccountBlockedException.class);

        assertThat(sessionsOf(registered.accountId())).isZero();
        assertThat(sessions.authenticate(raced.sessionToken())).isEmpty();
        assertThat(access.loginJournal(10, Actor.developer()))
                .extracting(LoginEventView::outcome, LoginEventView::accountId)
                .startsWith(tuple(LoginOutcome.BLOCKED, registered.accountId()));
    }

    @Test
    void sessionOfUnblockedCardStays() {
        var registered = signInFirstTime(new FakeProfile("u2", "+79215550000", null, "Оля", null));

        writer.rejectIfBlocked(registered, Provider.FAKE, CLIENT);

        assertThat(sessions.authenticate(registered.sessionToken())).isPresent();
        writer.rejectIfBlocked(new SignedIn("gone", UUID.randomUUID()), Provider.FAKE, CLIENT);
    }

    private SignedIn signInFirstTime(FakeProfile profile) {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fake.issueCode(profile);
        var result = loginFlow.complete(
                Provider.FAKE, new AuthorizationResponse(code, start.state().state(), null), start.state(), CLIENT);
        assertThat(result).isInstanceOf(RegistrationRequired.class);
        return loginFlow.completeRegistration(
                ((RegistrationRequired) result).ticket(),
                new CompleteRegistration(profile.displayName(), profile.phone(), "2026-09-25"),
                CLIENT);
    }

    private int sessionsOf(UUID accountId) {
        entityManager.flush();
        return jdbc.sql("select count(*) from identity.session where account_id = :accountId")
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
    }
}
