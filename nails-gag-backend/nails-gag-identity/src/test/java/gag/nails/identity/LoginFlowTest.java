package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ModuleTest
class LoginFlowTest extends IdentityTestSupport {

    @Test
    void firstLoginRequiresRegistration() {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        assertThat(start.authorizationUri().toString()).contains(start.state().state());
        assertThat(start.state().purpose()).isEqualTo(LoginPurpose.LOGIN);
        assertThat(start.state().accountId()).isNull();
        assertThat(start.state().codeVerifier()).hasSizeBetween(43, 128);

        var result = complete(new FakeProfile("u1", "8 921 123-45-67", "Ann@Example.com", "Анна", null), start.state());

        assertThat(result).isInstanceOf(RegistrationRequired.class);
        var required = (RegistrationRequired) result;
        assertThat(required.ticket()).isNotBlank();
        assertThat(required.prefill()).isEqualTo(new RegistrationPrefill("Анна", "+79211234567", true));
        assertThat(loginJournal()).isEmpty();
    }

    @Test
    void stateMismatchFailsAndIsJournaled() {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fake.issueCode(profile("u1", "+79211234567"));

        assertThatThrownBy(() -> loginFlow.complete(
                        Provider.FAKE, new AuthorizationResponse(code, "forged", null), start.state(), CLIENT))
                .isInstanceOf(LoginFailedException.class);

        assertThat(loginJournal()).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(LoginOutcome.FAILED);
            assertThat(event.provider()).isEqualTo(Provider.FAKE);
            assertThat(event.accountId()).isNull();
            assertThat(event.ip()).isEqualTo(CLIENT.ip());
            assertThat(event.userAgent()).isEqualTo(CLIENT.userAgent());
        });
    }

    @Test
    void expiredStateFails() {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> complete(profile("u1", "+79211234567"), start.state()))
                .isInstanceOf(LoginFailedException.class);
        assertThat(loginJournal()).extracting(LoginEventView::outcome).containsExactly(LoginOutcome.FAILED);
    }

    @Test
    void providerFailureIsReportedAsUnavailable() {
        fake.failNextExchange();

        assertThatThrownBy(() -> complete(profile("u1", "+79211234567")))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(loginJournal()).isEmpty();
    }

    @Test
    void secondLoginOfSameSubjectSignsIn() {
        var first = signInFirstTime(profile("u1", "+79211234567"));
        var registeredAt = me(first).loginMethods().getFirst().lastUsedAt();
        clock.advance(Duration.ofDays(2));

        var second = signIn(profile("u1", "+79211234567"));

        assertThat(second.accountId()).isEqualTo(first.accountId());
        assertThat(second.sessionToken()).isNotEqualTo(first.sessionToken());
        assertThat(actor(second).accountId()).isEqualTo(first.accountId());
        assertThat(me(second).loginMethods()).singleElement().satisfies(method -> {
            assertThat(method.lastUsedAt()).isEqualTo(registeredAt.plus(Duration.ofDays(2)));
            assertThat(method.linkedAt()).isEqualTo(registeredAt);
        });
        assertThat(loginJournal())
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.SIGNED_IN, LoginOutcome.REGISTERED);
    }

    @Test
    void loginRefreshesNameAndAvatar() {
        var first = signInFirstTime(new FakeProfile("u1", "+79211234567", null, "Аня", "https://a/1.png"));
        assertThat(me(first).displayName()).isEqualTo("Аня");

        var second = signIn(new FakeProfile("u1", "+79211234567", null, "Анна Петрова", "https://a/2.png"));

        var me = me(second);
        assertThat(me.displayName()).isEqualTo("Анна Петрова");
        assertThat(me.avatarUrl()).isEqualTo("https://a/2.png");
        assertThat(me.loginMethods()).singleElement().satisfies(method -> {
            assertThat(method.displayName()).isEqualTo("Анна Петрова");
            assertThat(method.avatarUrl()).isEqualTo("https://a/2.png");
        });
    }

    @Test
    void loginWithSamePhoneFromOtherProviderJoinsAccount() {
        var yandex = signInFirstTime(profile("yandex-1", "+79211234567"));

        var vk = signIn(profile("vk-1", "8 (921) 123-45-67"));

        assertThat(vk.accountId()).isEqualTo(yandex.accountId());
        assertThat(me(vk).loginMethods()).hasSize(2);
        assertThat(me(vk).displayName()).isEqualTo("Клиент vk-1");
        assertThat(clientId(vk)).isEqualTo(clientId(yandex));
    }

    @Test
    void loginWithSameEmailJoinsAccount() {
        var yandex = signInFirstTime(profile("yandex-1", null, "ann@example.com"));

        var vk = signIn(profile("vk-1", null, "ANN@example.com"));

        assertThat(vk.accountId()).isEqualTo(yandex.accountId());
        assertThat(me(vk).loginMethods()).hasSize(2);
    }

    @Test
    void phoneMatchWinsOverEmailMatch() {
        var a = signInFirstTime(profile("a", "+79211234567"));
        var b = signInFirstTime(profile("b", null, "b@example.com"));

        var signedIn = signIn(profile("c", "+79211234567", "b@example.com"));

        assertThat(signedIn.accountId()).isEqualTo(a.accountId());
        assertThat(me(a).loginMethods()).hasSize(2);
        assertThat(me(b).loginMethods()).hasSize(1);
    }

    @Test
    void loginMatchingMasterCardSignsIntoItsAccountOrOffersAttachment() {
        var card = clients.create(new CreateClient("Мария", "Иванова", null, "+79211234567", null, null), master);
        clients.setPrepaymentRequired(card.id(), true, "не пришла", master);

        var required = registrationRequired(new FakeProfile("u1", null, null, "Маша", null));
        assertThat(required.prefill()).isEqualTo(new RegistrationPrefill("Маша", null, false));

        var attached = registrationRequired(new FakeProfile("u2", "+79211234567", null, "Маша", null));
        assertThat(attached.prefill()).isEqualTo(new RegistrationPrefill("Маша", "+79211234567", true));

        var signedIn = loginFlow.completeRegistration(
                attached.ticket(), new CompleteRegistration("Маша", "+79211234567", POLICY_VERSION), CLIENT);

        assertThat(clientId(signedIn)).isEqualTo(card.id());
        var view = clientQueries.get(card.id(), master);
        assertThat(view.accountId()).isEqualTo(signedIn.accountId());
        assertThat(view.firstName()).isEqualTo("Мария");
        assertThat(view.prepaymentRequired()).isTrue();
        assertThat(clientQueries.prepaymentHistory(card.id(), master)).hasSize(1);

        // Карточка с аккаунтом, номер введён мастером позже: вход по телефону ведёт в её аккаунт.
        var other = signInFirstTime(profile("u3", null));
        clients.changePhone(clientId(other), "+79215550000", master);
        assertThat(signIn(profile("u4", "+79215550000")).accountId()).isEqualTo(other.accountId());
    }

    @Test
    void blockedCardCannotSignIn() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        clients.block(clientId(signedIn), "грубит", master);

        assertThatThrownBy(() -> complete(profile("u1", "+79211234567"))).isInstanceOf(AccountBlockedException.class);
        assertThatThrownBy(() -> complete(profile("u2", "+79211234567"))).isInstanceOf(AccountBlockedException.class);

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        assertThat(loginJournal())
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.BLOCKED, LoginOutcome.BLOCKED, LoginOutcome.REGISTERED);
        assertThat(loginJournal())
                .filteredOn(event -> event.outcome() == LoginOutcome.BLOCKED)
                .extracting(LoginEventView::accountId)
                .containsOnly(signedIn.accountId());

        // Заблокированная карточка без аккаунта тоже закрыта для входа.
        var card = clients.create(new CreateClient("Оля", null, null, "+79215550000", null, null), master);
        clients.block(card.id(), null, master);
        assertThatThrownBy(() -> complete(profile("u3", "+79215550000"))).isInstanceOf(AccountBlockedException.class);
        assertThat(loginJournal().getFirst().accountId()).isNull();
    }

    /**
     * Без транзакции теста: исход {@code BLOCKED} пишется в той же транзакции, что и отклонённый вход,
     * и должен пережить её завершение исключением. Всё зафиксированное тест убирает сам.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void blockedLoginIsJournaledDespiteRollback() {
        var profile = profile("blocked-" + UUID.randomUUID(), "+79219990001");
        SignedIn signedIn = null;
        UUID clientId = null;
        try {
            signedIn = signInFirstTime(profile);
            clientId = clientId(signedIn);
            clients.block(clientId, "грубит", master);

            assertThatThrownBy(() -> complete(profile)).isInstanceOf(AccountBlockedException.class);

            var accountId = signedIn.accountId();
            assertThat(loginJournal())
                    .filteredOn(event -> accountId.equals(event.accountId()))
                    .extracting(LoginEventView::outcome)
                    .containsExactly(LoginOutcome.BLOCKED, LoginOutcome.REGISTERED);
            assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        } finally {
            if (signedIn != null) {
                removeCommitted(signedIn.accountId(), clientId);
            }
        }
    }

    /**
     * Без транзакции теста: строка {@code FAILED} фиксируется своей короткой транзакцией, а больше ничего
     * не создаётся — ни аккаунта, ни карточки, ни сессии.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stateMismatchIsJournaledAndOpensNothing() {
        var profile = profile("forged-" + UUID.randomUUID(), "+79219990002");
        try {
            var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
            var code = fake.issueCode(profile);

            assertThatThrownBy(() -> loginFlow.complete(
                            Provider.FAKE, new AuthorizationResponse(code, "forged", null), start.state(), CLIENT))
                    .isInstanceOf(LoginFailedException.class);

            assertThat(loginJournal()).singleElement().satisfies(event -> {
                assertThat(event.outcome()).isEqualTo(LoginOutcome.FAILED);
                assertThat(event.accountId()).isNull();
            });
            assertThat(clientQueries.byPhone(profile.phone())).isEmpty();
            assertThat(jdbc.sql("select count(*) from identity.session")
                            .query(Integer.class)
                            .single())
                    .isZero();
        } finally {
            removeCommittedAnonymousEvents();
        }
    }
}
