package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.MergeTwoAccountsException;
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
    }

    @Test
    void twoAccountsCannotBeMerged() {
        var first = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, null));
        var second = clients.register(new RegisterClient(UUID.randomUUID(), "Маша", null, null));

        assertThatThrownBy(() -> clients.merge(first.id(), second.id(), master))
                .isInstanceOf(MergeTwoAccountsException.class);
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
