package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ClientQueriesTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    private final Actor master = Actor.master();
    private ClientView maria;
    private ClientView anna;

    @BeforeEach
    void createClients() {
        maria = clients.create(new CreateClient("Мария", "Иванова", null, "+79211234567", null, null), master);
        anna = clients.create(new CreateClient("Анна", "Петрова", null, "+79219876543", null, null), master);
    }

    @Test
    void getReturnsOwnDataToClientAndAnyToMaster() {
        assertThat(queries.get(maria.id(), Actor.client(maria.id())).firstName())
                .isEqualTo("Мария");
        assertThat(queries.get(anna.id(), master).firstName()).isEqualTo("Анна");

        assertThatThrownBy(() -> queries.get(anna.id(), Actor.client(maria.id())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID(), master)).isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void byPhoneNormalizesInput() {
        assertThat(queries.byPhone("8 921 123-45-67")).map(ClientView::id).contains(maria.id());
        assertThat(queries.byPhone("+70000000000")).isEmpty();
    }

    @Test
    void byAccountIdFindsRegisteredClient() {
        var accountId = UUID.randomUUID();
        var registered = clients.register(new RegisterClient(accountId, "Ольга", "+79215550001", null));

        assertThat(queries.byAccountId(accountId)).map(ClientView::id).contains(registered.id());
        assertThat(queries.byAccountId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void byEmailFindsTheOnlyCardIgnoringCaseAndStaysSilentOnDuplicates() {
        var card = clients.create(new CreateClient("Ольга", null, null, null, "Olga@Example.com", null), master);
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Ира", null, "ira@example.com"));

        assertThat(queries.byEmail("olga@example.COM")).map(ClientView::id).contains(card.id());
        assertThat(queries.byEmail("IRA@example.com")).map(ClientView::id).contains(registered.id());
        assertThat(queries.byEmail("nobody@example.com")).isEmpty();
        assertThat(queries.byEmail(" ")).isEmpty();

        clients.create(new CreateClient("Оля", null, null, null, "OLGA@example.com", null), master);

        assertThat(queries.byEmail("olga@example.com")).isEmpty();
    }

    @Test
    void searchMatchesNameOrPhoneFragmentCaseInsensitively() {
        assertThat(queries.search("мар", master)).extracting(ClientView::id).containsExactly(maria.id());
        assertThat(queries.search("ПЕТРОВА", master)).extracting(ClientView::id).containsExactly(anna.id());
        assertThat(queries.search("9876", master)).extracting(ClientView::id).containsExactly(anna.id());
        assertThat(queries.search("", master)).extracting(ClientView::id).containsExactly(anna.id(), maria.id());
    }

    @Test
    void searchEscapesLikeWildcards() {
        assertThat(queries.search("%", master)).isEmpty();
        assertThat(queries.search("_", master)).isEmpty();
        assertThat(queries.search("!", master)).isEmpty();
    }

    @Test
    void searchFindsPhoneTypedWithEight() {
        assertThat(queries.search("8 (921) 987", master))
                .extracting(ClientView::id)
                .containsExactly(anna.id());
        assertThat(queries.search("8921", master)).extracting(ClientView::id).containsExactly(anna.id(), maria.id());
    }

    @Test
    void searchIsForMasterOnly() {
        assertThatThrownBy(() -> queries.search("мар", Actor.client(maria.id())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
