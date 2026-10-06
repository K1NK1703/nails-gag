package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.error.RoleBuiltInException;
import gag.nails.identity.error.RoleCodeTakenException;
import gag.nails.identity.error.RoleInUseException;
import gag.nails.identity.error.RoleNotFoundException;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Актор администратора — аккаунт, получивший {@code DEVELOPER} через bootstrap при регистрации. */
@ModuleTest
class AccessAdministrationTest extends IdentityTestSupport {

    private SignedIn developer;
    private Actor admin;

    @BeforeEach
    void signInDeveloper() {
        developer = developer();
        admin = actor(developer);
    }

    @Test
    void builtInRolesArePresent() {
        var roles = access.roles(admin);

        assertThat(roles)
                .extracting(RoleView::code, RoleView::title, RoleView::builtIn)
                .containsExactly(tuple("DEVELOPER", "Разработчик", true), tuple("MASTER", "Мастер", true));
        assertThat(role("MASTER").grants()).containsExactlyInAnyOrderEntriesOf(Actor.masterAccess());
        assertThat(role("DEVELOPER").grants()).containsExactlyInAnyOrderEntriesOf(Actor.developerAccess());
    }

    @Test
    void developerCreatesRoleAndAssignsIt() {
        var helper = signInFirstTime(profile("helper", "+79211234567"));
        var grants = Map.of(Section.SCHEDULE, AccessLevel.READ, Section.BOOKINGS, AccessLevel.WRITE);

        var role = access.createRole(new CreateRole("ASSISTANT", "Помощник", grants), admin);
        var view = access.assignRole(helper.accountId(), role.id(), admin);

        assertThat(role.code()).isEqualTo("ASSISTANT");
        assertThat(role.builtIn()).isFalse();
        assertThat(role.grants()).containsExactlyInAnyOrderEntriesOf(grants);
        assertThat(view.roles()).extracting(RoleView::id).containsExactly(role.id());
        assertThat(view.clientId()).isEqualTo(clientId(helper));
        assertThat(view.displayName()).isEqualTo("Клиент helper");
        assertThat(view.blocked()).isFalse();
        assertThat(view.overrides()).isEmpty();
        assertThat(view.effective()).containsExactlyInAnyOrderEntriesOf(allSections(grants));
        assertThat(actor(helper).access()).containsExactlyInAnyOrderEntriesOf(allSections(grants));
        assertThat(access.roles(admin)).extracting(RoleView::code).containsExactly("ASSISTANT", "DEVELOPER", "MASTER");
        assertThat(access.staff(admin))
                .extracting(AccountAccessView::accountId)
                .containsExactly(developer.accountId(), helper.accountId());
        assertThat(accessJournal())
                .extracting(AccessChangeView::kind, AccessChangeView::actorAccountId, AccessChangeView::targetAccountId)
                .containsExactly(
                        tuple(AccessChangeKind.ROLE_ASSIGNED, developer.accountId(), helper.accountId()),
                        tuple(AccessChangeKind.ROLE_CREATED, developer.accountId(), null),
                        tuple(AccessChangeKind.BOOTSTRAP, null, developer.accountId()));
        assertThat(accessJournal().getFirst().roleId()).isEqualTo(role.id());
    }

    @Test
    void roleCodeMustBeUnique() {
        assertThatThrownBy(() -> access.createRole(new CreateRole("MASTER", "Ещё мастер", Map.of()), admin))
                .isInstanceOf(RoleCodeTakenException.class)
                .satisfies(
                        e -> assertThat(((RoleCodeTakenException) e).params()).containsEntry("code", "MASTER"));
    }

    @Test
    void roleCodeFormatIsValidated() {
        assertThatThrownBy(() -> access.createRole(new CreateRole("assistant", "Помощник", Map.of()), admin))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> access.createRole(new CreateRole("A", "Помощник", Map.of()), admin))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> access.createRole(new CreateRole("ASSISTANT", " ", Map.of()), admin))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> access.updateRole(role("MASTER").id(), new UpdateRole("", Map.of()), admin))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void noneGrantsAreDroppedAndUnknownIdsAreReported() {
        var role = access.createRole(
                new CreateRole("VIEWER", "Наблюдатель", Map.of(Section.FINANCE, AccessLevel.NONE)), admin);

        assertThat(role.grants()).isEmpty();
        assertThatThrownBy(() -> access.assignRole(UUID.randomUUID(), role.id(), admin))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> access.assignRole(developer.accountId(), UUID.randomUUID(), admin))
                .isInstanceOf(RoleNotFoundException.class);
        assertThatThrownBy(() -> access.account(UUID.randomUUID(), admin)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void overrideBeatsRoleBothWays() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);
        var client = signInFirstTime(profile("client", "+79215550000"));

        access.setOverride(staff.accountId(), Section.FINANCE, AccessLevel.NONE, admin);
        access.setOverride(client.accountId(), Section.SCHEDULE, AccessLevel.READ, admin);

        assertThat(actor(staff).level(Section.FINANCE)).isEqualTo(AccessLevel.NONE);
        assertThat(actor(staff).level(Section.BOOKINGS)).isEqualTo(AccessLevel.WRITE);
        assertThat(actor(client).level(Section.SCHEDULE)).isEqualTo(AccessLevel.READ);
        assertThat(actor(client).isStaff()).isTrue();
        assertThat(access.account(staff.accountId(), admin).overrides())
                .containsExactly(Map.entry(Section.FINANCE, AccessLevel.NONE));
        assertThat(access.staff(admin))
                .extracting(AccountAccessView::accountId)
                .containsExactly(developer.accountId(), staff.accountId(), client.accountId());

        access.setOverride(staff.accountId(), Section.FINANCE, null, admin);
        access.setOverride(client.accountId(), Section.SCHEDULE, null, admin);

        assertThat(actor(staff).level(Section.FINANCE)).isEqualTo(AccessLevel.WRITE);
        assertThat(actor(client).isStaff()).isFalse();
        assertThat(access.staff(admin))
                .extracting(AccountAccessView::accountId)
                .containsExactly(developer.accountId(), staff.accountId());
        assertThat(accessJournal())
                .filteredOn(change -> change.kind() == AccessChangeKind.OVERRIDE_SET
                        || change.kind() == AccessChangeKind.OVERRIDE_CLEARED)
                .extracting(
                        AccessChangeView::kind,
                        AccessChangeView::section,
                        AccessChangeView::oldLevel,
                        AccessChangeView::newLevel)
                .containsExactly(
                        tuple(AccessChangeKind.OVERRIDE_CLEARED, Section.SCHEDULE, AccessLevel.READ, null),
                        tuple(AccessChangeKind.OVERRIDE_CLEARED, Section.FINANCE, AccessLevel.NONE, null),
                        tuple(AccessChangeKind.OVERRIDE_SET, Section.SCHEDULE, null, AccessLevel.READ),
                        tuple(AccessChangeKind.OVERRIDE_SET, Section.FINANCE, null, AccessLevel.NONE));
    }

    @Test
    void maxOfRolesWins() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        var reader =
                access.createRole(new CreateRole("READER", "Чтение", Map.of(Section.CATALOG, AccessLevel.READ)), admin);
        var writer = access.createRole(
                new CreateRole("WRITER", "Запись", Map.of(Section.CATALOG, AccessLevel.WRITE)), admin);
        access.assignRole(staff.accountId(), reader.id(), admin);
        access.assignRole(staff.accountId(), writer.id(), admin);

        assertThat(actor(staff).level(Section.CATALOG)).isEqualTo(AccessLevel.WRITE);
        assertThat(access.account(staff.accountId(), admin).roles())
                .extracting(RoleView::code)
                .containsExactly("READER", "WRITER");

        access.revokeRole(staff.accountId(), writer.id(), admin);

        assertThat(actor(staff).level(Section.CATALOG)).isEqualTo(AccessLevel.READ);
    }

    @Test
    void builtInRoleCannotBeDeletedButMayBeEdited() {
        var master = role("MASTER");
        var developerRole = role("DEVELOPER");

        assertThatThrownBy(() -> access.deleteRole(master.id(), admin)).isInstanceOf(RoleBuiltInException.class);

        var grants = new EnumMap<>(master.grants());
        grants.put(Section.FINANCE, AccessLevel.READ);
        var updated = access.updateRole(master.id(), new UpdateRole("Мастер салона", grants), admin);

        assertThat(updated.code()).isEqualTo("MASTER");
        assertThat(updated.title()).isEqualTo("Мастер салона");
        assertThat(updated.builtIn()).isTrue();
        assertThat(updated.grants()).containsEntry(Section.FINANCE, AccessLevel.READ);
        assertThat(accessJournal().getFirst())
                .extracting(
                        AccessChangeView::kind,
                        AccessChangeView::roleId,
                        AccessChangeView::section,
                        AccessChangeView::oldLevel,
                        AccessChangeView::newLevel)
                .containsExactly(
                        AccessChangeKind.ROLE_UPDATED,
                        master.id(),
                        Section.FINANCE,
                        AccessLevel.WRITE,
                        AccessLevel.READ);

        var demoted = new EnumMap<>(developerRole.grants());
        demoted.put(Section.ACCESS, AccessLevel.READ);
        assertThatThrownBy(() -> access.updateRole(developerRole.id(), new UpdateRole("Разработчик", demoted), admin))
                .isInstanceOf(RoleBuiltInException.class);
        assertThat(access.updateRole(developerRole.id(), new UpdateRole("Админ", developerRole.grants()), admin)
                        .title())
                .isEqualTo("Админ");
        assertThatThrownBy(() -> access.updateRole(UUID.randomUUID(), new UpdateRole("Нет", Map.of()), admin))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void assignedRoleCannotBeDeleted() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        var role = access.createRole(
                new CreateRole("TEMP", "Временная", Map.of(Section.CATALOG, AccessLevel.READ)), admin);
        access.assignRole(staff.accountId(), role.id(), admin);

        assertThatThrownBy(() -> access.deleteRole(role.id(), admin))
                .isInstanceOf(RoleInUseException.class)
                .satisfies(e -> assertThat(((RoleInUseException) e).params()).containsEntry("roleId", role.id()));

        access.revokeRole(staff.accountId(), role.id(), admin);
        access.deleteRole(role.id(), admin);

        assertThat(access.roles(admin)).extracting(RoleView::code).doesNotContain("TEMP");
        assertThatThrownBy(() -> access.deleteRole(role.id(), admin)).isInstanceOf(RoleNotFoundException.class);
        assertThat(accessJournal().getFirst().kind()).isEqualTo(AccessChangeKind.ROLE_DELETED);
    }

    /**
     * В общей транзакции теста отклонённая операция оставляет свою запись видимой (в бою её откатывает
     * транзакция), поэтому после каждого отказа состояние возвращается операцией обратного знака.
     */
    @Test
    void lastAccessAdminCannotBeDemoted() {
        var developerRole = role("DEVELOPER");

        assertThatThrownBy(() -> access.revokeRole(developer.accountId(), developerRole.id(), admin))
                .isInstanceOf(LastAccessAdminException.class);
        access.assignRole(developer.accountId(), developerRole.id(), admin);

        assertThatThrownBy(() -> access.setOverride(developer.accountId(), Section.ACCESS, AccessLevel.READ, admin))
                .isInstanceOf(LastAccessAdminException.class);
        access.setOverride(developer.accountId(), Section.ACCESS, null, admin);
        assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);

        var custom = access.createRole(
                new CreateRole("ADMIN", "Администратор", Map.of(Section.ACCESS, AccessLevel.WRITE)), admin);
        access.assignRole(developer.accountId(), custom.id(), admin);
        access.revokeRole(developer.accountId(), developerRole.id(), admin);
        assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);

        var demoted = new UpdateRole("Администратор", Map.of(Section.ACCESS, AccessLevel.READ));
        assertThatThrownBy(() -> access.updateRole(custom.id(), demoted, admin))
                .isInstanceOf(LastAccessAdminException.class);
        access.updateRole(custom.id(), new UpdateRole("Администратор", custom.grants()), admin);

        var second = signInFirstTime(profile("second", "+79211234567"));
        access.assignRole(second.accountId(), developerRole.id(), admin);

        access.updateRole(custom.id(), demoted, admin);

        assertThat(actor(developer).level(Section.ACCESS)).isEqualTo(AccessLevel.READ);
        assertThat(actor(second).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
    }

    @Test
    void readerOfAccessSeesButDoesNotChange() {
        var reader = signInFirstTime(profile("reader", "+79211234567"));
        access.setOverride(reader.accountId(), Section.ACCESS, AccessLevel.READ, admin);
        var readerActor = actor(reader);

        assertThat(access.roles(readerActor)).hasSize(2);
        assertThat(access.staff(readerActor)).hasSize(2);
        assertThat(access.account(developer.accountId(), readerActor).effective())
                .containsEntry(Section.ACCESS, AccessLevel.WRITE);
        assertThat(access.accessJournal(10, readerActor)).isNotEmpty();
        assertThat(access.loginJournal(10, readerActor)).isNotEmpty();

        assertThatThrownBy(() -> access.createRole(new CreateRole("NEW_ROLE", "Новая", Map.of()), readerActor))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() ->
                        access.assignRole(reader.accountId(), role("DEVELOPER").id(), readerActor))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.setOverride(reader.accountId(), Section.ACCESS, AccessLevel.WRITE, readerActor))
                .isInstanceOf(AccessDeniedException.class);

        var client = signInFirstTime(profile("client", "+79215550000"));
        assertThatThrownBy(() -> access.roles(actor(client))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.loginJournal(10, actor(client))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void idempotentAssignAndRevokeWriteNoJournal() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        var masterRole = role("MASTER");
        var before = accessJournal().size();

        access.assignRole(staff.accountId(), masterRole.id(), admin);
        access.assignRole(staff.accountId(), masterRole.id(), admin);
        access.setOverride(staff.accountId(), Section.FINANCE, AccessLevel.READ, admin);
        access.setOverride(staff.accountId(), Section.FINANCE, AccessLevel.READ, admin);
        access.setOverride(staff.accountId(), Section.CATALOG, null, admin);
        access.updateRole(masterRole.id(), new UpdateRole(masterRole.title(), masterRole.grants()), admin);

        assertThat(accessJournal()).hasSize(before + 2);
        assertThat(access.account(staff.accountId(), admin).roles()).hasSize(1);

        access.revokeRole(staff.accountId(), masterRole.id(), admin);
        access.revokeRole(staff.accountId(), masterRole.id(), admin);

        assertThat(accessJournal()).hasSize(before + 3);
        assertThat(access.account(staff.accountId(), admin).roles()).isEmpty();
    }

    @Test
    void journalsAreNewestFirstAndLimited() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        clock.advance(Duration.ofMinutes(1));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);
        clock.advance(Duration.ofMinutes(1));
        access.setOverride(staff.accountId(), Section.FINANCE, AccessLevel.NONE, admin);
        clock.advance(Duration.ofMinutes(1));
        signIn(profile("staff", "+79211234567"));

        assertThat(access.accessJournal(2, admin))
                .extracting(AccessChangeView::kind)
                .containsExactly(AccessChangeKind.OVERRIDE_SET, AccessChangeKind.ROLE_ASSIGNED);
        assertThat(access.accessJournal(500, admin))
                .extracting(AccessChangeView::kind)
                .containsExactly(
                        AccessChangeKind.OVERRIDE_SET, AccessChangeKind.ROLE_ASSIGNED, AccessChangeKind.BOOTSTRAP);
        assertThat(access.loginJournal(1, admin))
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.SIGNED_IN);
        assertThat(access.loginJournal(10, admin))
                .extracting(LoginEventView::outcome)
                .containsExactly(LoginOutcome.SIGNED_IN, LoginOutcome.REGISTERED, LoginOutcome.REGISTERED);

        assertThatThrownBy(() -> access.accessJournal(0, admin)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> access.accessJournal(501, admin)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> access.loginJournal(0, admin)).isInstanceOf(ValidationException.class);
    }

    private static Map<Section, AccessLevel> allSections(Map<Section, AccessLevel> grants) {
        var all = new EnumMap<Section, AccessLevel>(Section.class);
        for (var section : Section.values()) {
            all.put(section, grants.getOrDefault(section, AccessLevel.NONE));
        }
        return all;
    }
}
