package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.event.ClientBlocked;
import gag.nails.clients.event.ClientMerged;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.MergeResult;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.MonthDay;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class ClientMergeTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    MutableClock clock;

    private final Actor master = Actor.master();

    @Test
    void cardIsMergedIntoRegisteredClient(PublishedEvents events) {
        var card = clients.create(
                new CreateClient("Мария", "Иванова", null, "+79211234567", null, MonthDay.of(3, 8)), master);
        clients.updateMasterNote(card.id(), "любит квадрат", master);
        clients.recordConsent(
                card.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.ENTERED_BY_MASTER),
                master);
        clients.applySystemPrepaymentChange(
                card.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null));
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, "m@example.com"));

        clock.advance(Duration.ofMinutes(1));
        var result = clients.merge(card.id(), registered.id(), master);

        assertThat(result).isEqualTo(new MergeResult(registered.id(), card.id()));
        var survivor = queries.get(registered.id(), master);
        assertThat(survivor.phone()).isEqualTo("+79211234567");
        assertThat(survivor.lastName()).isEqualTo("Иванова");
        assertThat(survivor.email()).isEqualTo("m@example.com");
        assertThat(survivor.birthday()).isEqualTo(MonthDay.of(3, 8));
        assertThat(survivor.prepaymentRequired()).isTrue();
        assertThat(queries.masterNote(registered.id(), master)).contains("любит квадрат");
        assertThat(queries.hasCurrentConsents(registered.id(), Set.of(ConsentKind.PERSONAL_DATA)))
                .isTrue();
        assertThat(queries.prepaymentHistory(registered.id(), master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.MERGED, PrepaymentFlagReason.NO_SHOW);
        assertThat(queries.byId(card.id())).isEmpty();
        assertThat(queries.byPhone("+79211234567")).map(ClientView::id).contains(registered.id());
        assertThat(events.ofType(ClientMerged.class)
                        .matching(e -> e.removedClientId().equals(card.id())
                                && e.survivorClientId().equals(registered.id())))
                .hasSize(1);
    }

    @Test
    void survivorIsTheOneWithAccountRegardlessOfArgumentOrder() {
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, null));
        var card = clients.create(new CreateClient("Маша", null, null, "+79211234567", null, null), master);

        var result = clients.merge(registered.id(), card.id(), master);

        assertThat(result.survivorId()).isEqualTo(registered.id());
        assertThat(result.removedId()).isEqualTo(card.id());
    }

    @Test
    void twoCardsMergeIntoTheTargetAndKeepTargetData() {
        var target = clients.create(new CreateClient("Мария", "Иванова", null, null, "a@example.com", null), master);
        var source = clients.create(
                new CreateClient("Маша", "Петрова", null, "+79211234567", "b@example.com", null), master);

        var result = clients.merge(source.id(), target.id(), master);

        assertThat(result.survivorId()).isEqualTo(target.id());
        var survivor = queries.get(target.id(), master);
        assertThat(survivor.lastName()).isEqualTo("Иванова");
        assertThat(survivor.email()).isEqualTo("a@example.com");
        assertThat(survivor.phone()).isEqualTo("+79211234567");
        assertThat(survivor.prepaymentRequired()).isFalse();
        assertThat(queries.prepaymentHistory(target.id(), master)).isEmpty();
        assertThat(queries.byPhone("+79211234567")).map(ClientView::id).contains(target.id());
    }

    @Test
    void survivorKeepsOwnPhoneFlagAndAppendsNote() {
        var target = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var source = clients.create(new CreateClient("Маша", null, null, "+79219876543", null, null), master);
        clients.updateMasterNote(target.id(), "квадрат", master);
        clients.updateMasterNote(source.id(), "аллергия на гель", master);
        clients.setPrepaymentRequired(target.id(), true, null, master);
        clients.setPrepaymentRequired(source.id(), true, null, master);

        clock.advance(Duration.ofMinutes(1));
        clients.merge(source.id(), target.id(), master);

        assertThat(queries.byPhone("+79211234567")).map(ClientView::id).contains(target.id());
        assertThat(queries.byPhone("+79219876543")).isEmpty();
        assertThat(queries.masterNote(target.id(), master)).contains("квадрат\nаллергия на гель");
        assertThat(queries.prepaymentHistory(target.id(), master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.MASTER_SET, PrepaymentFlagReason.MASTER_SET);
    }

    @Test
    void twoRegisteredClientsAreMergedIntoTarget(PublishedEvents events) {
        var sourceAccount = UUID.randomUUID();
        var targetAccount = UUID.randomUUID();
        var source = clients.register(new RegisterClient(sourceAccount, "Мария", "+79211234567", null));
        var target = clients.register(new RegisterClient(targetAccount, "Маша", null, "m@example.com"));

        var result = clients.merge(source.id(), target.id(), master);

        assertThat(result).isEqualTo(new MergeResult(target.id(), source.id()));
        var survivor = queries.get(target.id(), master);
        assertThat(survivor.accountId()).isEqualTo(targetAccount);
        assertThat(survivor.phone()).isEqualTo("+79211234567");
        assertThat(queries.byId(source.id())).isEmpty();
        assertThat(events.ofType(ClientMerged.class)
                        .matching(e -> e.removedClientId().equals(source.id())
                                && e.survivorClientId().equals(target.id())
                                && sourceAccount.equals(e.removedAccountId())
                                && targetAccount.equals(e.survivorAccountId())))
                .hasSize(1);
    }

    @Test
    void mergeEventCarriesAccountsWhenOnlySurvivorHasOne(PublishedEvents events) {
        var account = UUID.randomUUID();
        var registered = clients.register(new RegisterClient(account, "Мария", null, null));
        var card = clients.create(new CreateClient("Маша", null, null, "+79211234567", null, null), master);

        clients.merge(card.id(), registered.id(), master);

        assertThat(events.ofType(ClientMerged.class)
                        .matching(e -> e.removedClientId().equals(card.id())
                                && e.survivorClientId().equals(registered.id())
                                && e.removedAccountId() == null
                                && account.equals(e.survivorAccountId())))
                .hasSize(1);
    }

    @Test
    void mergedClientIsBlockedIfEitherWas() {
        var target = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var source = clients.create(new CreateClient("Маша", null, null, "+79219876543", null, null), master);
        clients.block(source.id(), "не платит", master);

        clients.merge(source.id(), target.id(), master);

        assertThat(queries.get(target.id(), master).blocked()).isTrue();
        assertThat(queries.blockReason(target.id(), master)).contains("не платит");

        var blockedTarget = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        var blockedSource = clients.create(new CreateClient("Анна", null, null, null, null, null), master);
        clients.block(blockedTarget.id(), "своя причина", master);
        clients.block(blockedSource.id(), "чужая причина", master);

        clients.merge(blockedSource.id(), blockedTarget.id(), master);

        assertThat(queries.get(blockedTarget.id(), master).blocked()).isTrue();
        assertThat(queries.blockReason(blockedTarget.id(), master)).contains("своя причина");
    }

    @Test
    void mergeBlockingSurvivorPublishesClientBlocked(PublishedEvents events) {
        var account = UUID.randomUUID();
        var registered = clients.register(new RegisterClient(account, "Мария", null, null));
        var card = clients.create(new CreateClient("Маша", null, null, "+79211234567", null, null), master);
        clients.block(card.id(), "не платит", master);
        clock.advance(Duration.ofMinutes(1));

        clients.merge(card.id(), registered.id(), master);

        assertThat(queries.get(registered.id(), master).blocked()).isTrue();
        assertThat(events.ofType(ClientBlocked.class)
                        .matching(e -> e.clientId().equals(registered.id())
                                && account.equals(e.accountId())
                                && e.occurredAt().equals(clock.instant())))
                .hasSize(1);
    }

    @Test
    void mergeOfAlreadyBlockedSurvivorPublishesNothing(PublishedEvents events) {
        var target = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        var source = clients.create(new CreateClient("Маша", null, null, null, null, null), master);
        clients.block(target.id(), "своя причина", master);
        clients.block(source.id(), "чужая причина", master);
        var unblockedSource = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        clock.advance(Duration.ofMinutes(1));

        clients.merge(source.id(), target.id(), master);
        clients.merge(unblockedSource.id(), target.id(), master);

        assertThat(queries.get(target.id(), master).blocked()).isTrue();
        assertThat(events.ofType(ClientBlocked.class)
                        .matching(e -> e.occurredAt().equals(clock.instant())))
                .isEmpty();
    }

    @Test
    void clientMergesOwnSecondCard(PublishedEvents events) {
        var ownAccount = UUID.randomUUID();
        var otherAccount = UUID.randomUUID();
        var own = clients.register(new RegisterClient(ownAccount, "Мария", null, null));
        var other = clients.register(new RegisterClient(otherAccount, "Маша", "+79211234567", null));
        var me = Actor.client(own.id());

        var result = clients.mergeOwn(other.id(), me);

        assertThat(result).isEqualTo(new MergeResult(own.id(), other.id()));
        var survivor = queries.get(own.id(), me);
        assertThat(survivor.accountId()).isEqualTo(ownAccount);
        assertThat(survivor.phone()).isEqualTo("+79211234567");
        assertThat(queries.byId(other.id())).isEmpty();
        assertThat(events.ofType(ClientMerged.class)
                        .matching(e -> e.removedClientId().equals(other.id())
                                && e.survivorClientId().equals(own.id())
                                && otherAccount.equals(e.removedAccountId())
                                && ownAccount.equals(e.survivorAccountId())))
                .hasSize(1);

        var card = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        assertThatThrownBy(() -> clients.mergeOwn(card.id(), master)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.mergeOwn(own.id(), me)).isInstanceOf(ValidationException.class);

        clients.block(card.id(), null, master);
        assertThatThrownBy(() -> clients.mergeOwn(card.id(), me)).isInstanceOf(ClientBlockedException.class);
        clients.unblock(card.id(), master);
        clients.block(own.id(), null, master);
        assertThatThrownBy(() -> clients.mergeOwn(card.id(), me)).isInstanceOf(ClientBlockedException.class);
        assertThat(queries.byId(card.id())).isPresent();
    }

    @Test
    void mergeRequiresTwoDifferentExistingClientsAndMaster() {
        var client = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        assertThatThrownBy(() -> clients.merge(client.id(), client.id(), master))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> clients.merge(client.id(), UUID.randomUUID(), master))
                .isInstanceOf(ClientNotFoundException.class);
        assertThatThrownBy(() -> clients.merge(client.id(), client.id(), Actor.client(client.id())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
