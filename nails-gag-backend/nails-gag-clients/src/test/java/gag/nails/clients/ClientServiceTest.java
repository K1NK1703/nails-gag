package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.clients.model.ClientSource;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import java.time.MonthDay;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ClientServiceTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    private final Actor master = Actor.master();

    @Test
    void masterCreatesCardWithNormalizedPhone() {
        var created = clients.create(
                new CreateClient("Мария", null, null, "8 (921) 123-45-67", null, MonthDay.of(3, 8)), master);

        assertThat(created.source()).isEqualTo(ClientSource.ADDED_BY_MASTER);
        assertThat(created.phone()).isEqualTo("+79211234567");
        assertThat(created.birthday()).isEqualTo(MonthDay.of(3, 8));
        assertThat(created.accountId()).isNull();
        assertThat(created.prepaymentRequired()).isFalse();
    }

    @Test
    void cardMayHaveNoPhone() {
        var created = clients.create(new CreateClient("Аня", null, null, null, null, null), master);

        assertThat(created.phone()).isNull();
    }

    @Test
    void clientCannotCreateCards() {
        assertThatThrownBy(() -> clients.create(
                        new CreateClient("Аня", null, null, null, null, null), Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void malformedPhoneIsRejected() {
        assertThatThrownBy(() -> clients.create(new CreateClient("Аня", null, null, "12345", null, null), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "phone"));
    }

    @Test
    void phoneIsUniqueAcrossClients() {
        clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);

        assertThatThrownBy(() ->
                        clients.create(new CreateClient("Другая", null, null, "+7 921 123 45 67", null, null), master))
                .isInstanceOf(PhoneTakenException.class);
    }

    @Test
    void registrationCreatesSelfRegisteredClientWithAccount() {
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", "m@example.com"));

        assertThat(registered.source()).isEqualTo(ClientSource.SELF_REGISTERED);
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(registered.email()).isEqualTo("m@example.com");
    }

    @Test
    void registrationWithKnownPhoneAttachesAccountToExistingCard() {
        var card = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Maria", "+79211234567", null));

        assertThat(registered.id()).isEqualTo(card.id());
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(registered.firstName()).isEqualTo("Мария");
        assertThat(registered.source()).isEqualTo(ClientSource.ADDED_BY_MASTER);
    }

    @Test
    void clientUpdatesOwnDataButNotOthers() {
        var me = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
        var other = clients.create(new CreateClient("Аня", null, null, null, null, null), master);
        var update = new UpdateClient("Мария", "Иванова", null, "m@example.com", MonthDay.of(3, 8));

        var updated = clients.update(me.id(), update, Actor.client(me.id()));
        assertThat(updated.lastName()).isEqualTo("Иванова");

        assertThatThrownBy(() -> clients.update(other.id(), update, Actor.client(me.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onlyMasterChangesPhone() {
        var me = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        assertThat(clients.changePhone(me.id(), "+79990000001", master).phone()).isEqualTo("+79990000001");
        assertThatThrownBy(() -> clients.changePhone(me.id(), "+79990000002", Actor.client(me.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void masterNoteIsVisibleOnlyToMaster() {
        var me = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        clients.updateMasterNote(me.id(), "любит короткий квадрат", master);

        assertThat(queries.masterNote(me.id(), master)).contains("любит короткий квадрат");
        assertThatThrownBy(() -> queries.masterNote(me.id(), Actor.client(me.id())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void tenDigitPhoneIsNormalized() {
        var created = clients.create(new CreateClient("Мария", null, null, "921.123.45.67", null, null), master);

        assertThat(created.phone()).isEqualTo("+79211234567");
    }

    @Test
    void repeatedRegistrationReturnsTheSameClient() {
        var accountId = UUID.randomUUID();
        var first = clients.register(new RegisterClient(accountId, "Мария", "+79211234567", null));

        var second = clients.register(new RegisterClient(accountId, "Maria", "+79990000001", "m@example.com"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.phone()).isEqualTo("+79211234567");
        assertThat(second.firstName()).isEqualTo("Мария");
        assertThat(second.email()).isNull();
        assertThat(queries.byPhone("+79990000001")).isEmpty();
    }

    @Test
    void registrationWithPhoneOfRegisteredClientIsRejected() {
        clients.register(new RegisterClient(UUID.randomUUID(), "Мария", "+79211234567", null));

        assertThatThrownBy(() ->
                        clients.register(new RegisterClient(UUID.randomUUID(), "Другая", "8 921 123-45-67", null)))
                .isInstanceOf(PhoneTakenException.class);
    }

    @Test
    void changePhoneToTakenNumberIsRejected() {
        var maria = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var other = clients.create(new CreateClient("Аня", null, null, null, null, null), master);

        assertThatThrownBy(() -> clients.changePhone(other.id(), "8 921 123-45-67", master))
                .isInstanceOf(PhoneTakenException.class);
        assertThat(clients.changePhone(maria.id(), "8 921 123-45-67", master).phone())
                .isEqualTo("+79211234567");
    }

    @Test
    void blankNoteErasesIt() {
        var me = clients.create(new CreateClient("Мария", null, null, null, null, null), master);

        clients.updateMasterNote(me.id(), "  любит короткий квадрат  ", master);
        assertThat(queries.masterNote(me.id(), master)).contains("любит короткий квадрат");

        clients.updateMasterNote(me.id(), "   ", master);
        assertThat(queries.masterNote(me.id(), master)).isEmpty();
    }

    @Test
    void registrationAttachesToCardWithSameEmail() {
        var card = clients.create(new CreateClient("Мария", "Иванова", null, null, "Maria@Example.com", null), master);
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Maria", null, "maria@example.com"));

        assertThat(registered.id()).isEqualTo(card.id());
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(registered.firstName()).isEqualTo("Мария");
        assertThat(registered.email()).isEqualTo("Maria@Example.com");
    }

    @Test
    void registrationDoesNotGuessBetweenTwoCardsWithSameEmail() {
        var first = clients.create(new CreateClient("Мария", null, null, null, "maria@example.com", null), master);
        var second = clients.create(new CreateClient("Маша", null, null, null, "MARIA@example.com", null), master);
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Maria", null, "maria@example.com"));

        assertThat(registered.id()).isNotIn(first.id(), second.id());
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(queries.get(first.id(), master).accountId()).isNull();
        assertThat(queries.get(second.id(), master).accountId()).isNull();
    }

    @Test
    void registrationSkipsCardsWithSameEmailThatAlreadyHaveAnAccount() {
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Мария", null, "maria@example.com"));
        var accountId = UUID.randomUUID();

        var second = clients.register(new RegisterClient(accountId, "Maria", null, "maria@example.com"));

        assertThat(second.id()).isNotEqualTo(registered.id());
        assertThat(second.accountId()).isEqualTo(accountId);
    }

    @Test
    void phoneMatchWinsOverEmailMatch() {
        var byPhone = clients.create(new CreateClient("Мария", null, null, "+79211234567", null, null), master);
        var byEmail = clients.create(new CreateClient("Маша", null, null, null, "maria@example.com", null), master);
        var accountId = UUID.randomUUID();

        var registered = clients.register(new RegisterClient(accountId, "Maria", "+79211234567", "maria@example.com"));

        assertThat(registered.id()).isEqualTo(byPhone.id());
        assertThat(registered.accountId()).isEqualTo(accountId);
        assertThat(queries.get(byEmail.id(), master).accountId()).isNull();
    }

    @Test
    void unknownClientIsReported() {
        var missing = UUID.randomUUID();

        assertThatThrownBy(() -> clients.update(missing, new UpdateClient("Аня", null, null, null, null), master))
                .isInstanceOf(ClientNotFoundException.class);
    }
}
