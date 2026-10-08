package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LoginEventView;
import gag.nails.identity.model.LoginOutcome;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.Provider;
import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Что фиксируется, а что откатывается — на настоящих транзакциях (см. {@link CommittingIdentityTest}).
 * Аннотация транзакции стоит прямо на классе: см. там же, почему.
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdentityCommitsTest extends CommittingIdentityTest {

    /** Блокировка единственного администратора откатывается целиком: карточка не заблокирована, сессия жива. */
    @Test
    void blockingLastAdminIsRejected() {
        var developer = developer();
        var clientId = clientId(developer);

        assertThatThrownBy(() -> clients.block(clientId, "по ошибке", master))
                .isInstanceOf(LastAccessAdminException.class);

        assertThat(clientQueries.get(clientId, master).blocked()).isFalse();
        assertThat(sessions.authenticate(developer.sessionToken())).isPresent();
        assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
    }

    /**
     * Удаление сначала стирает всё и только потом проверяет правило последнего администратора: отказ
     * откатывает и обезличивание, и удаление; со вторым администратором удаление проходит.
     */
    @Test
    void lastAdminCannotDeleteAccount() {
        var developer = developer();
        var developerClient = clientId(developer);
        var developerActor = actor(developer);

        assertThatThrownBy(() -> accounts.delete(developerActor)).isInstanceOf(LastAccessAdminException.class);

        assertThat(sessions.authenticate(developer.sessionToken())).isPresent();
        assertThat(clientQueries.get(developerClient, master).erased()).isFalse();
        assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);

        var second = signInFirstTime(profile("second", "+79211234567"));
        access.assignRole(second.accountId(), role("DEVELOPER").id(), developerActor);
        accounts.delete(developerActor);

        assertThat(sessions.authenticate(developer.sessionToken())).isEmpty();
        assertThat(clientQueries.get(developerClient, master).erased()).isTrue();
        assertThat(actor(second).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
    }

    /** Исход {@code BLOCKED} пишется в транзакции отклонённого входа и переживает её завершение исключением. */
    @Test
    void blockedLoginIsJournaledDespiteRollback() {
        var profile = profile("blocked", "+79219990001");
        var signedIn = signInFirstTime(profile);
        clients.block(clientId(signedIn), "грубит", master);

        assertThatThrownBy(() -> complete(profile)).isInstanceOf(AccountBlockedException.class);

        assertThat(loginJournal())
                .filteredOn(event -> signedIn.accountId().equals(event.accountId()))
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.BLOCKED, LoginOutcome.REGISTERED);
        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
    }

    /** Строка {@code FAILED} фиксируется своей короткой транзакцией, а больше ничего не создаётся. */
    @Test
    void stateMismatchIsJournaledAndOpensNothing() {
        var client = new ClientInfo("203.0.113.9", "JUnit state-mismatch");
        var profile = profile("forged", "+79219990002");
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fake.issueCode(profile);

        assertThatThrownBy(() -> loginFlow.complete(
                        Provider.FAKE, new AuthorizationResponse(code, "forged", null), start.state(), client))
                .isInstanceOf(LoginFailedException.class);

        assertThat(loginJournal())
                .filteredOn(event -> client.userAgent().equals(event.userAgent()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.outcome()).isEqualTo(LoginOutcome.FAILED);
                    assertThat(event.accountId()).isNull();
                });
        assertThat(clientQueries.byPhone(profile.phone())).isEmpty();
        assertThat(jdbc.sql("select count(*) from identity.session")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    /** Отказ из-за устаревшей версии политики откатывает и потребление билета: тот же билет годен дальше. */
    @Test
    void failedRegistrationKeepsTicketUsable() {
        var profile = profile("retry", "+79219990003");
        var ticket = registrationRequired(profile).ticket();

        assertThatThrownBy(() -> loginFlow.completeRegistration(
                        ticket, new CompleteRegistration("Анна", profile.phone(), "2020-01-01"), CLIENT))
                .isInstanceOf(ConsentVersionOutdatedException.class);
        assertThat(clientQueries.byPhone(profile.phone())).isEmpty();

        var signedIn = loginFlow.completeRegistration(
                ticket, new CompleteRegistration("Анна", profile.phone(), POLICY_VERSION), CLIENT);

        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();
        assertThat(clientQueries.byPhone(profile.phone()))
                .map(ClientView::accountId)
                .contains(signedIn.accountId());
    }
}
