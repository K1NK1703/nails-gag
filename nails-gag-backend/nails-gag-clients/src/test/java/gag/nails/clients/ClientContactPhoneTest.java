package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ClientContactPhoneTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor master = Actor.master();

    @Test
    void clientSetsOwnContactPhone() {
        var maria = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", "+79211234567", null));
        var other = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        var me = Actor.client(maria.id());

        var changed = clients.changeContactPhone(maria.id(), "8 (999) 000-00-01", me);

        assertThat(changed.contactPhone()).isEqualTo("+79990000001");
        assertThat(changed.phone()).isEqualTo("+79211234567");
        assertThatThrownBy(() -> clients.changeContactPhone(other.id(), "+79990000002", me))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.changeContactPhone(maria.id(), "12345", me))
                .isInstanceOf(ValidationException.class);

        assertThat(clients.changeContactPhone(maria.id(), "", me).contactPhone())
                .isNull();
        assertThat(clients.changeContactPhone(other.id(), "+79990000003", master)
                        .contactPhone())
                .isEqualTo("+79990000003");
    }

    @Test
    void contactPhoneMayRepeatAnotherClientsPhone() {
        var maria = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var anna = clients.create(new CreateClient("Аня", null, null, null, null, null), master);

        clients.changeContactPhone(anna.id(), "+79211234567", Actor.client(anna.id()));
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(anna.id(), master).contactPhone()).isEqualTo("+79211234567");
        assertThat(queries.get(maria.id(), master).phone()).isEqualTo("+79211234567");
        assertThat(queries.byPhone("+79211234567")).map(ClientView::id).contains(maria.id());
    }

    @Test
    void contactPhoneDoesNotAttachAccount() {
        var cardA = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var clientB = clients.register(new RegisterClient(UUID.randomUUID(), "Маша", null, null));
        clients.changeContactPhone(clientB.id(), "+79211234567", Actor.client(clientB.id()));
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Maria", "+79211234567", null));

        assertThat(registered.id()).isEqualTo(cardA.id());
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(queries.get(clientB.id(), master).accountId()).isEqualTo(clientB.accountId());
    }

    @Test
    void searchFindsByContactPhone() {
        var maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        clients.create(new CreateClient("Аня", null, null, "+79219876543", null, null), master);
        clients.changeContactPhone(maria.id(), "+79990000001", master);

        assertThat(queries.search("999 000", master)).extracting(ClientView::id).containsExactly(maria.id());
        assertThat(queries.search("8 999", master)).extracting(ClientView::id).containsExactly(maria.id());
    }

    @Test
    void confirmedPhoneFillsEmptyPhoneOnly() {
        var maria = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, null));
        var anna = clients.register(new RegisterClient(UUID.randomUUID(), "Аня", "+79219876543", null));
        var olga = clients.register(new RegisterClient(UUID.randomUUID(), "Оля", null, null));

        assertThat(clients.confirmPhone(maria.id(), "8 921 123-45-67")).isTrue();
        entityManager.flush();
        entityManager.clear();
        assertThat(queries.get(maria.id(), master).phone()).isEqualTo("+79211234567");

        assertThat(clients.confirmPhone(anna.id(), "+79990000001")).isFalse();
        assertThat(queries.get(anna.id(), master).phone()).isEqualTo("+79219876543");

        assertThat(clients.confirmPhone(olga.id(), "+79211234567")).isFalse();
        assertThat(queries.get(olga.id(), master).phone()).isNull();
        assertThat(queries.byPhone("+79211234567")).map(ClientView::id).contains(maria.id());
    }
}
