package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.model.MergeResult;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.testsupport.ModuleTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Синхронные слушатели {@code ClientBlocked} и {@code ClientMerged} в транзакции операции {@code clients}. */
@ModuleTest
class IdentityListenersTest extends IdentityTestSupport {

    @Test
    void blockingClosesSessions() {
        var developer = developer();
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        var second = signIn(profile("u1", "+79211234567"));

        clients.block(clientId(signedIn), "грубит", master);

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(second.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(developer.sessionToken())).isPresent();
        assertThat(access.account(signedIn.accountId(), actor(developer)).blocked())
                .isTrue();
    }

    /**
     * Без транзакции теста: иначе откат блокировки увидеть нельзя — записи отклонённой операции остались
     * бы в общей транзакции до конца теста. Поэтому всё, что тест зафиксировал, он же и убирает.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void blockingLastAdminIsRejected() {
        SignedIn developer = null;
        UUID clientId = null;
        try {
            developer = developer();
            clientId = clientId(developer);
            var developerClient = clientId;
            assertThatThrownBy(() -> clients.block(developerClient, "по ошибке", master))
                    .isInstanceOf(LastAccessAdminException.class);

            assertThat(clientQueries.get(clientId, master).blocked()).isFalse();
            assertThat(sessions.authenticate(developer.sessionToken())).isPresent();
            assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
        } finally {
            if (developer != null) {
                removeCommitted(developer.accountId(), clientId);
            }
        }
    }

    @Test
    void mergeMovesLoginMethodsRolesAndOverrides() {
        var developer = developer();
        var admin = actor(developer);
        var first = signInFirstTime(profile("a", "+79211234567"));
        var second = signInFirstTime(profile("b", "+79215550000"));
        var secondSession = signIn(profile("b", "+79215550000"));
        access.assignRole(second.accountId(), role("MASTER").id(), admin);
        access.setOverride(second.accountId(), Section.FINANCE, AccessLevel.NONE, admin);
        access.setOverride(second.accountId(), Section.CATALOG, AccessLevel.READ, admin);
        access.setOverride(first.accountId(), Section.FINANCE, AccessLevel.WRITE, admin);

        var result = clients.merge(clientId(second), clientId(first), master);

        assertThat(result).isEqualTo(new MergeResult(clientId(first), result.removedId()));
        assertThat(sessions.authenticate(second.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(secondSession.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(first.sessionToken())).isPresent();
        assertThat(me(first).loginMethods()).hasSize(2);
        assertThat(signIn(profile("b", "+79215550000")).accountId()).isEqualTo(first.accountId());
        var merged = access.account(first.accountId(), admin);
        assertThat(merged.roles()).extracting(RoleView::code).containsExactly("MASTER");
        assertThat(merged.overrides())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(Section.FINANCE, AccessLevel.WRITE, Section.CATALOG, AccessLevel.READ));
        assertThat(merged.effective()).containsEntry(Section.FINANCE, AccessLevel.WRITE);
        assertThat(merged.effective()).containsEntry(Section.CATALOG, AccessLevel.READ);
        assertThatThrownBy(() -> access.account(second.accountId(), admin))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(access.staff(admin))
                .extracting(AccountAccessView::accountId)
                .containsExactly(developer.accountId(), first.accountId());
    }

    @Test
    void mergeWithOneAccountLeavesItToTheSurvivorCard() {
        developer();
        var signedIn = signInFirstTime(profile("a", "+79211234567"));
        var card = clients.create(new CreateClient("Маша", null, null, "+79215550000", null, null), master);

        var result = clients.merge(clientId(signedIn), card.id(), master);

        assertThat(result.survivorId()).isEqualTo(clientId(signedIn));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();
        assertThat(me(signedIn).loginMethods()).hasSize(1);
    }
}
