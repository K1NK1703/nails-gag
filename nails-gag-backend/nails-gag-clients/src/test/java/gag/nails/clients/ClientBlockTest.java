package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.event.ClientBlocked;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class ClientBlockTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor master = Actor.master();

    @Test
    void masterBlocksAndUnblocksClient(PublishedEvents events) {
        var accountId = UUID.randomUUID();
        var maria = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", null));

        var blocked = clients.block(maria.id(), "грубит", master);
        entityManager.flush();
        entityManager.clear();

        assertThat(blocked.blocked()).isTrue();
        var read = queries.get(maria.id(), master);
        assertThat(read.blocked()).isTrue();
        assertThat(read.erased()).isFalse();
        assertThat(queries.blockReason(maria.id(), master)).contains("грубит");
        assertThat(events.ofType(ClientBlocked.class)
                        .matching(e -> e.clientId().equals(maria.id())
                                && accountId.equals(e.accountId())
                                && e.occurredAt().equals(TestTimeConfiguration.START)))
                .hasSize(1);

        var unblocked = clients.unblock(maria.id(), master);
        entityManager.flush();
        entityManager.clear();

        assertThat(unblocked.blocked()).isFalse();
        assertThat(queries.get(maria.id(), master).blocked()).isFalse();
        assertThat(queries.blockReason(maria.id(), master)).isEmpty();
    }

    @Test
    void repeatedBlockingChangesTheReasonAndPublishesAgain(PublishedEvents events) {
        var maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        clients.block(maria.id(), "первая причина", master);

        clients.block(maria.id(), "вторая причина", master);

        assertThat(queries.blockReason(maria.id(), master)).contains("вторая причина");
        assertThat(events.ofType(ClientBlocked.class).matching(e -> e.clientId().equals(maria.id())))
                .hasSize(2);
        assertThat(clients.unblock(maria.id(), master).blocked()).isFalse();
        assertThat(clients.unblock(maria.id(), master).blocked()).isFalse();
    }

    @Test
    void blockReasonIsHiddenFromClient() {
        var maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        clients.block(maria.id(), "грубит", master);

        assertThatThrownBy(() -> queries.blockReason(maria.id(), Actor.client(maria.id())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(queries.get(maria.id(), Actor.client(maria.id())).blocked()).isTrue();
        assertThat(ClientView.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("blockReason");
    }

    @Test
    void blockReasonLongerThan500IsRejected() {
        var maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        assertThatThrownBy(() -> clients.block(maria.id(), "п".repeat(501), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "reason"));

        var exact = "п".repeat(500);
        clients.block(maria.id(), exact, master);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.blockReason(maria.id(), master)).contains(exact);
    }

    @Test
    void registrationMatchingBlockedCardIsRejected() {
        var byPhone = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var byEmail = clients.create(new CreateClient("Аня", null, null, null, "anna@example.com", null), master);
        clients.block(byPhone.id(), null, master);
        clients.block(byEmail.id(), "не платит", master);

        assertThatThrownBy(() -> clients.register(new RegisterClient(UUID.randomUUID(), "Maria", "+79211234567", null)))
                .isInstanceOf(ClientBlockedException.class)
                .satisfies(
                        e -> assertThat(((ClientBlockedException) e).params()).containsEntry("clientId", byPhone.id()));
        assertThatThrownBy(
                        () -> clients.register(new RegisterClient(UUID.randomUUID(), "Anna", null, "Anna@Example.com")))
                .isInstanceOf(ClientBlockedException.class);
        assertThat(queries.get(byPhone.id(), master).accountId()).isNull();
        assertThat(queries.get(byEmail.id(), master).accountId()).isNull();
    }

    @Test
    void blockingNeedsWriteAccess() {
        var reader = Actor.of(null, null, Map.of(Section.CLIENTS, AccessLevel.READ));
        var maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        assertThatThrownBy(() -> clients.block(maria.id(), null, reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.unblock(maria.id(), reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.block(maria.id(), null, Actor.client(maria.id())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(queries.blockReason(maria.id(), reader)).isEmpty();
        assertThat(queries.get(maria.id(), master).blocked()).isFalse();
    }
}
