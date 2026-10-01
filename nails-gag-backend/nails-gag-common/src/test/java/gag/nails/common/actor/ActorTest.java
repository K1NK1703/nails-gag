package gag.nails.common.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActorTest {

    private static final UUID CLIENT_ID = UUID.fromString("019a0000-0000-7000-8000-000000000001");

    @Test
    void masterAndDeveloperActAsMaster() {
        assertThat(Actor.master().isMaster()).isTrue();
        assertThat(Actor.developer().isMaster()).isTrue();
        assertThat(Actor.client(CLIENT_ID).isMaster()).isFalse();
    }

    @Test
    void clientActsOnlyOnOwnData() {
        var client = Actor.client(CLIENT_ID);

        assertThat(client.isClient(CLIENT_ID)).isTrue();
        assertThat(client.isClient(UUID.randomUUID())).isFalse();
        assertThat(Actor.master().isClient(CLIENT_ID)).isFalse();
    }

    @Test
    void oneAccountMayHoldSeveralRoles() {
        var masterWhoIsAlsoClient = new Actor(Set.of(Role.MASTER, Role.CLIENT), CLIENT_ID);

        assertThat(masterWhoIsAlsoClient.isMaster()).isTrue();
        assertThat(masterWhoIsAlsoClient.isClient(CLIENT_ID)).isTrue();
    }

    @Test
    void requireMasterRejectsClient() {
        assertThatThrownBy(() -> Actor.client(CLIENT_ID).requireMaster())
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> assertThat(((AccessDeniedException) e).code()).isEqualTo("common.access_denied"));
    }

    @Test
    void requireMasterOrSelfAllowsMasterAndOwnerOnly() {
        Actor.master().requireMasterOrSelf(CLIENT_ID);
        Actor.client(CLIENT_ID).requireMasterOrSelf(CLIENT_ID);

        assertThatThrownBy(() -> Actor.client(CLIENT_ID).requireMasterOrSelf(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireClientIdRejectsActorWithoutClient() {
        assertThat(Actor.client(CLIENT_ID).requireClientId()).isEqualTo(CLIENT_ID);
        assertThatThrownBy(() -> Actor.master().requireClientId()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void clientRoleRequiresClientId() {
        assertThatThrownBy(() -> new Actor(Set.of(Role.CLIENT), null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rolesMustNotBeEmpty() {
        assertThatThrownBy(() -> new Actor(Set.of(), null)).isInstanceOf(ValidationException.class);
    }
}
