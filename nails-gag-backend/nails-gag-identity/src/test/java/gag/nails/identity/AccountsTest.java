package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ModuleTest
class AccountsTest extends IdentityTestSupport {

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void meShowsMethodsAccessAndClient() {
        var developer = developer();
        var signedIn = signInFirstTime(new FakeProfile("yandex-1", "+79211234567", null, "Аня", "https://a/1.png"));
        access.assignRole(signedIn.accountId(), role("MASTER").id(), actor(developer));
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LINK, actor(signedIn));
        clock.advance(Duration.ofMinutes(1));
        complete(new FakeProfile("vk-1", null, null, "Анна (VK)", "https://a/2.png"), start.state());

        var me = me(signedIn);

        assertThat(me.id()).isEqualTo(signedIn.accountId());
        assertThat(me.clientId()).isEqualTo(clientId(signedIn));
        assertThat(me.createdAt()).isEqualTo(TestTimeConfiguration.START);
        assertThat(me.displayName()).isEqualTo("Анна (VK)");
        assertThat(me.avatarUrl()).isEqualTo("https://a/2.png");
        assertThat(me.loginMethods())
                .extracting(LoginMethodView::provider, LoginMethodView::displayName, LoginMethodView::avatarUrl)
                .containsExactly(
                        tuple(Provider.FAKE, "Аня", "https://a/1.png"),
                        tuple(Provider.FAKE, "Анна (VK)", "https://a/2.png"));
        assertThat(me.loginMethods().getFirst().linkedAt()).isEqualTo(TestTimeConfiguration.START);
        assertThat(me.loginMethods().getLast().linkedAt())
                .isEqualTo(TestTimeConfiguration.START.plus(Duration.ofMinutes(1)));
        assertThat(me.access()).containsAllEntriesOf(Actor.masterAccess());
        assertThat(me.access()).containsEntry(Section.ACCESS, AccessLevel.NONE);

        // В сессии имя и аватарка — способа входа, которым вошли, а не последнего использованного.
        var authenticated = sessions.authenticate(signedIn.sessionToken()).orElseThrow();
        assertThat(authenticated.account().displayName()).isEqualTo("Аня");
        assertThat(authenticated.account().avatarUrl()).isEqualTo("https://a/1.png");

        assertThatThrownBy(() -> accounts.me(Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accounts.me(Actor.of(UUID.randomUUID(), null, Map.of())))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void deleteErasesCardAndRemovesAccount() {
        var developer = developer();
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        var second = signIn(profile("u1", "+79211234567"));
        access.setOverride(signedIn.accountId(), Section.SCHEDULE, AccessLevel.READ, actor(developer));
        var clientId = clientId(signedIn);

        accounts.delete(actor(signedIn));
        // Ссылки журнала входов обнуляет каскад базы: перечитываем, а не берём из кэша сущностей.
        entityManager.flush();
        entityManager.clear();

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(second.sessionToken())).isEmpty();
        var card = clientQueries.byId(clientId).orElseThrow();
        assertThat(card.erased()).isTrue();
        assertThat(card.accountId()).isNull();
        assertThat(card.phone()).isNull();
        assertThat(clientQueries.byAccountId(signedIn.accountId())).isEmpty();
        assertThatThrownBy(() -> access.account(signedIn.accountId(), actor(developer)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(access.staff(actor(developer)))
                .extracting(AccountAccessView::accountId)
                .containsExactly(developer.accountId());
        assertThat(loginJournal())
                .filteredOn(event -> event.outcome() == LoginOutcome.SIGNED_IN)
                .singleElement()
                .satisfies(event -> assertThat(event.accountId()).isNull());

        var again = complete(profile("u1", "+79211234567"));

        assertThat(again).isInstanceOf(RegistrationRequired.class);
        var fresh = loginFlow.completeRegistration(
                ((RegistrationRequired) again).ticket(),
                new CompleteRegistration("Анна", "+79211234567", POLICY_VERSION),
                CLIENT);
        assertThat(fresh.accountId()).isNotEqualTo(signedIn.accountId());
        assertThat(clientId(fresh)).isNotEqualTo(clientId);
    }

    /**
     * Без транзакции теста: удаление сначала стирает всё и только потом проверяет правило последнего
     * администратора, так что откат отказа виден лишь на настоящей транзакции. Всё зафиксированное тест
     * убирает сам.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lastAdminCannotDeleteAccount() {
        SignedIn developer = null;
        SignedIn second = null;
        UUID developerClient = null;
        UUID secondClient = null;
        try {
            developer = developer();
            developerClient = clientId(developer);
            var developerActor = actor(developer);

            assertThatThrownBy(() -> accounts.delete(developerActor)).isInstanceOf(LastAccessAdminException.class);

            assertThat(sessions.authenticate(developer.sessionToken())).isPresent();
            assertThat(clientQueries.get(developerClient, master).erased()).isFalse();
            assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);

            second = signInFirstTime(profile("second-" + UUID.randomUUID(), "+79211234567"));
            secondClient = clientId(second);
            access.assignRole(second.accountId(), role("DEVELOPER").id(), developerActor);
            accounts.delete(developerActor);

            assertThat(sessions.authenticate(developer.sessionToken())).isEmpty();
            assertThat(clientQueries.get(developerClient, master).erased()).isTrue();
            assertThat(actor(second).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
        } finally {
            if (developer != null) {
                removeCommitted(developer.accountId(), developerClient);
            }
            if (second != null) {
                removeCommitted(second.accountId(), secondClient);
            }
        }
    }

    @Test
    void clientWithoutAccessDeletesAccountEvenWhenThereIsNoAdmin() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        var clientId = clientId(signedIn);

        accounts.delete(actor(signedIn));

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        assertThat(clientQueries.byId(clientId).orElseThrow().erased()).isTrue();
    }
}
