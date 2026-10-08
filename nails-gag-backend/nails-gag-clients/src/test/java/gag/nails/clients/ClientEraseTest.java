package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.event.ClientErased;
import gag.nails.clients.model.ClientSource;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.MonthDay;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class ClientEraseTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor master = Actor.master();

    @Test
    void erasedClientKeepsOnlyAnonymousCard(PublishedEvents events) {
        var accountId = UUID.randomUUID();
        var maria = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", "m@example.com"));
        clients.update(
                maria.id(),
                new UpdateClient("Мария", "Иванова", "Петровна", "m@example.com", MonthDay.of(3, 8)),
                master);
        clients.changeContactPhone(maria.id(), "+79990000001", master);
        clients.updateMasterNote(maria.id(), "любит квадрат", master);
        clients.recordConsent(
                maria.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.SITE_CHECKBOX),
                Actor.client(maria.id()));
        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null));

        clients.erase(maria.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.byId(maria.id())).hasValueSatisfying(erased -> {
            assertThat(erased.firstName()).isEqualTo("Удалённый клиент");
            assertThat(erased.lastName()).isNull();
            assertThat(erased.patronymic()).isNull();
            assertThat(erased.phone()).isNull();
            assertThat(erased.contactPhone()).isNull();
            assertThat(erased.email()).isNull();
            assertThat(erased.birthday()).isNull();
            assertThat(erased.accountId()).isNull();
            assertThat(erased.source()).isEqualTo(ClientSource.SELF_REGISTERED);
            assertThat(erased.prepaymentRequired()).isTrue();
            assertThat(erased.erased()).isTrue();
            assertThat(erased.blocked()).isFalse();
        });
        assertThat(queries.masterNote(maria.id(), master)).isEmpty();
        assertThat(queries.hasCurrentConsents(maria.id(), Set.of(ConsentKind.PERSONAL_DATA)))
                .isTrue();
        assertThat(queries.consents(maria.id(), master)).hasSize(1);
        assertThat(queries.prepaymentHistory(maria.id(), master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.NO_SHOW);
        assertThat(queries.byPhone("+79211234567")).isEmpty();
        assertThat(events.ofType(ClientErased.class)
                        .matching(e -> e.clientId().equals(maria.id())
                                && e.occurredAt().equals(TestTimeConfiguration.START)))
                .hasSize(1);
    }

    @Test
    void eraseIsIdempotent(PublishedEvents events) {
        var maria = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, null));
        clients.erase(maria.id());

        assertThatCode(() -> clients.erase(maria.id())).doesNotThrowAnyException();

        assertThat(events.ofType(ClientErased.class).matching(e -> e.clientId().equals(maria.id())))
                .hasSize(1);
    }

    @Test
    void erasedClientIsHiddenFromSearchAndCannotBeChanged() {
        var maria = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", "+79211234567", null));
        var anna = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        clients.erase(maria.id());

        assertThat(queries.search("", master)).extracting(ClientView::id).containsExactly(anna.id());
        assertThat(queries.search("Удал", master)).isEmpty();

        var rename = new UpdateClient("Мария", null, null, null, null);
        assertThatThrownBy(() -> clients.update(maria.id(), rename, master)).isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.changePhone(maria.id(), "+79211234567", master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.changeContactPhone(maria.id(), "+79211234567", master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.updateMasterNote(maria.id(), "заметка", master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.setPrepaymentRequired(maria.id(), true, null, master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.recordConsent(
                        maria.id(),
                        new RecordConsent(ConsentKind.OFFER, "2026-09-25", ConsentSource.ENTERED_BY_MASTER),
                        master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.block(maria.id(), null, master)).isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.merge(maria.id(), anna.id(), master))
                .isInstanceOf(ClientErasedException.class)
                .satisfies(e -> assertThat(((ClientErasedException) e).params()).containsEntry("clientId", maria.id()));
        assertThatThrownBy(() -> clients.merge(anna.id(), maria.id(), master))
                .isInstanceOf(ClientErasedException.class);
        assertThatThrownBy(() -> clients.mergeOwn(maria.id(), Actor.client(anna.id())))
                .isInstanceOf(ClientErasedException.class);

        assertThatCode(() -> clients.applySystemPrepaymentChange(
                        maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null)))
                .doesNotThrowAnyException();
        assertThat(queries.byId(maria.id())).map(ClientView::prepaymentRequired).contains(true);
        assertThat(queries.byId(anna.id())).isPresent();
    }

    @Test
    void erasedAccountMayRegisterAgain() {
        var accountId = UUID.randomUUID();
        var first = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", null));
        clients.erase(first.id());

        var second = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", null));

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.accountId()).isEqualTo(accountId);
        assertThat(second.phone()).isEqualTo("+79211234567");
        assertThat(second.erased()).isFalse();
        assertThat(queries.byId(first.id())).map(ClientView::erased).contains(true);
    }

    @Test
    void blockedClientStaysBlockedAfterErase() {
        var maria = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, null));
        clients.block(maria.id(), "грубит", master);

        clients.erase(maria.id());
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.byId(maria.id())).hasValueSatisfying(erased -> {
            assertThat(erased.blocked()).isTrue();
            assertThat(erased.erased()).isTrue();
        });
        assertThat(queries.blockReason(maria.id(), master)).isEmpty();
    }
}
