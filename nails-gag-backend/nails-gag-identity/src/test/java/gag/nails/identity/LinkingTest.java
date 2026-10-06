package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.model.ConsentKind;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.testsupport.ModuleTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@ModuleTest
class LinkingTest extends IdentityTestSupport {

    @Test
    void linkingUnownedMethodAddsItToAccount() {
        var signedIn = signInFirstTime(profile("yandex-1", "+79211234567"));
        var actor = actor(signedIn);

        var result = link(actor, new FakeProfile("vk-1", null, "ann@example.com", "Анна (VK)", null));

        assertThat(result).isEqualTo(new Linked(signedIn.accountId()));
        assertThat(me(signedIn).loginMethods())
                .extracting(LoginMethodView::displayName)
                .containsExactly("Клиент yandex-1", "Анна (VK)");
        assertThat(signIn(profile("vk-1", null)).accountId()).isEqualTo(signedIn.accountId());
        assertThat(loginJournal())
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.SIGNED_IN, LoginOutcome.LINKED, LoginOutcome.REGISTERED);
    }

    @Test
    void linkingOwnMethodIsNoOp() {
        var signedIn = signInFirstTime(profile("yandex-1", "+79211234567"));

        var result = link(actor(signedIn), profile("yandex-1", "+79211234567"));

        assertThat(result).isEqualTo(new Linked(signedIn.accountId()));
        assertThat(me(signedIn).loginMethods()).hasSize(1);
    }

    @Test
    void linkingMethodOfAnotherAccountRequiresConfirmationAndMerges() {
        developer();
        var first = signInFirstTime(profile("yandex-1", "+79211234567"));
        var second = signInFirstTime(profile("vk-2", "+79215550000"));
        clients.updateMasterNote(clientId(second), "любит квадрат", master);
        clients.setPrepaymentRequired(clientId(second), true, null, master);
        var firstActor = actor(first);
        var secondClientId = clientId(second);

        var result = link(firstActor, profile("vk-2", "+79215550000"));

        assertThat(result).isInstanceOf(LinkConfirmationRequired.class);
        var ticket = ((LinkConfirmationRequired) result).ticket();
        assertThat(me(second).loginMethods()).hasSize(1);

        var linked = loginFlow.confirmLink(ticket, firstActor, CLIENT);

        assertThat(linked).isEqualTo(new Linked(first.accountId()));
        assertThat(sessions.authenticate(second.sessionToken())).isEmpty();
        assertThat(me(first).loginMethods()).hasSize(2);
        assertThat(signIn(profile("vk-2", "+79215550000")).accountId()).isEqualTo(first.accountId());
        assertThat(clientQueries.byId(secondClientId)).isEmpty();
        var card = clientQueries.get(clientId(first), master);
        assertThat(card.accountId()).isEqualTo(first.accountId());
        assertThat(card.prepaymentRequired()).isTrue();
        assertThat(clientQueries.masterNote(card.id(), master)).contains("любит квадрат");
        assertThat(clientQueries.hasCurrentConsents(card.id(), Set.of(ConsentKind.PERSONAL_DATA)))
                .isTrue();
        assertThatThrownBy(() -> loginFlow.confirmLink(ticket, firstActor, CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
    }

    @Test
    void confirmLinkBelongsToTheAccountThatStartedIt() {
        developer();
        var first = signInFirstTime(profile("yandex-1", "+79211234567"));
        var second = signInFirstTime(profile("vk-2", "+79215550000"));
        var third = signInFirstTime(profile("vk-3", "+79215550001"));
        var ticket = ((LinkConfirmationRequired) link(actor(first), profile("vk-2", "+79215550000"))).ticket();

        assertThatThrownBy(() -> loginFlow.confirmLink(ticket, actor(third), CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
        assertThatThrownBy(() -> loginFlow.confirmLink(ticket, Actor.client(UUID.randomUUID()), CLIENT))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(sessions.authenticate(second.sessionToken())).isPresent();
    }

    @Test
    void linkWithBlockedOtherAccountIsRejected() {
        developer();
        var first = signInFirstTime(profile("yandex-1", "+79211234567"));
        var second = signInFirstTime(profile("vk-2", "+79215550000"));
        clients.block(clientId(second), "грубит", master);

        assertThatThrownBy(() -> link(actor(first), profile("vk-2", "+79215550000")))
                .isInstanceOf(AccountBlockedException.class);
        assertThat(loginJournal().getFirst().outcome()).isEqualTo(LoginOutcome.BLOCKED);
        assertThat(loginJournal().getFirst().accountId()).isEqualTo(second.accountId());
        assertThat(me(first).loginMethods()).hasSize(1);
    }

    @Test
    void linkStartWithoutAccountIsRejected() {
        assertThatThrownBy(() -> loginFlow.start(Provider.FAKE, LoginPurpose.LINK, Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> loginFlow.start(Provider.FAKE, LoginPurpose.LINK, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    private LoginResult link(Actor actor, FakeProfile profile) {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LINK, actor);
        assertThat(start.state().purpose()).isEqualTo(LoginPurpose.LINK);
        assertThat(start.state().accountId()).isEqualTo(actor.accountId());
        return complete(profile, start.state());
    }
}
