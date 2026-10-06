package gag.nails.common.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.AccessDeniedException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActorTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("019a0000-0000-7000-8000-000000000010");
    private static final UUID CLIENT_ID = UUID.fromString("019a0000-0000-7000-8000-000000000001");

    @Test
    void masterWritesEverythingExceptDocumentsAndAccess() {
        var master = Actor.master();

        for (var section : new Section[] {
            Section.BOOKINGS,
            Section.SCHEDULE,
            Section.CLIENTS,
            Section.CATALOG,
            Section.BOOKING_SETTINGS,
            Section.FINANCE,
            Section.PORTFOLIO
        }) {
            assertThat(master.level(section)).as(section.name()).isEqualTo(AccessLevel.WRITE);
        }
        assertThat(master.level(Section.DOCUMENTS)).isEqualTo(AccessLevel.READ);
        assertThat(master.level(Section.ACCESS)).isEqualTo(AccessLevel.NONE);
    }

    @Test
    void developerWritesEverythingExceptFinance() {
        var developer = Actor.developer();

        for (var section : Section.values()) {
            var expected = section == Section.FINANCE ? AccessLevel.READ : AccessLevel.WRITE;
            assertThat(developer.level(section)).as(section.name()).isEqualTo(expected);
        }
    }

    @Test
    void clientHasNoSections() {
        var client = Actor.client(CLIENT_ID);

        for (var section : Section.values()) {
            assertThat(client.level(section)).as(section.name()).isEqualTo(AccessLevel.NONE);
        }
        assertThat(client.isStaff()).isFalse();
        assertThat(client.requireClientId()).isEqualTo(CLIENT_ID);
    }

    @Test
    void writeIncludesRead() {
        var writer = Actor.of(ACCOUNT_ID, null, Map.of(Section.CATALOG, AccessLevel.WRITE));
        var reader = Actor.of(ACCOUNT_ID, null, Map.of(Section.CATALOG, AccessLevel.READ));
        var nobody = Actor.of(ACCOUNT_ID, null, Map.of(Section.CATALOG, AccessLevel.NONE));

        assertThatCode(() -> writer.requireRead(Section.CATALOG)).doesNotThrowAnyException();
        assertThatCode(() -> writer.requireWrite(Section.CATALOG)).doesNotThrowAnyException();

        assertThatCode(() -> reader.requireRead(Section.CATALOG)).doesNotThrowAnyException();
        assertThatThrownBy(() -> reader.requireWrite(Section.CATALOG))
                .isInstanceOf(AccessDeniedException.class)
                .satisfies(e -> assertThat(((AccessDeniedException) e).code()).isEqualTo("common.access_denied"));

        assertThatThrownBy(() -> nobody.requireRead(Section.CATALOG)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> nobody.requireWrite(Section.CATALOG)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingSectionMeansNone() {
        var actor = Actor.of(ACCOUNT_ID, null, Map.of(Section.CATALOG, AccessLevel.READ));

        assertThat(actor.level(Section.SCHEDULE)).isEqualTo(AccessLevel.NONE);
        assertThat(actor.canRead(Section.SCHEDULE)).isFalse();
    }

    @Test
    void staffIsAnyoneWithASection() {
        var reader = Actor.of(ACCOUNT_ID, null, Map.of(Section.PORTFOLIO, AccessLevel.READ));

        assertThat(reader.isStaff()).isTrue();
        assertThat(Actor.of(ACCOUNT_ID, null, Map.of()).isStaff()).isFalse();
        assertThat(Actor.of(ACCOUNT_ID, null, Map.of(Section.CATALOG, AccessLevel.NONE))
                        .isStaff())
                .isFalse();
    }

    @Test
    void ownerPassesWithoutSection() {
        var client = Actor.client(CLIENT_ID);

        assertThatCode(() -> client.requireReadOrSelf(Section.CLIENTS, CLIENT_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> client.requireWriteOrSelf(Section.CLIENTS, CLIENT_ID))
                .doesNotThrowAnyException();

        var other = UUID.randomUUID();
        assertThatThrownBy(() -> client.requireReadOrSelf(Section.CLIENTS, other))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> client.requireWriteOrSelf(Section.CLIENTS, other))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffPassesForAnyClient() {
        var anyId = UUID.randomUUID();
        assertThatCode(() -> Actor.master().requireWriteOrSelf(Section.CLIENTS, anyId))
                .doesNotThrowAnyException();

        var reader = Actor.of(ACCOUNT_ID, null, Map.of(Section.CLIENTS, AccessLevel.READ));
        assertThatCode(() -> reader.requireReadOrSelf(Section.CLIENTS, anyId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> reader.requireWriteOrSelf(Section.CLIENTS, anyId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffMayAlsoBeClient() {
        var actor = Actor.of(ACCOUNT_ID, CLIENT_ID, Actor.masterAccess());

        assertThat(actor.isClient(CLIENT_ID)).isTrue();
        assertThat(actor.canWrite(Section.BOOKINGS)).isTrue();
        assertThat(actor.client()).contains(CLIENT_ID);
        assertThat(actor.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void requireClientIdRejectsActorWithoutClient() {
        assertThatThrownBy(() -> Actor.master().requireClientId()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void accessMapIsCopied() {
        var levels = new EnumMap<Section, AccessLevel>(Section.class);
        levels.put(Section.CATALOG, AccessLevel.READ);
        var actor = Actor.of(ACCOUNT_ID, null, levels);

        levels.put(Section.CATALOG, AccessLevel.WRITE);
        levels.put(Section.SCHEDULE, AccessLevel.WRITE);

        assertThat(actor.level(Section.CATALOG)).isEqualTo(AccessLevel.READ);
        assertThat(actor.level(Section.SCHEDULE)).isEqualTo(AccessLevel.NONE);
    }
}
