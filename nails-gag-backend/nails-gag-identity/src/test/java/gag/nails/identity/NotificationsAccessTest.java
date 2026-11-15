package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.identity.model.SignedIn;
import gag.nails.identity.model.StaffRecipient;
import gag.nails.testsupport.ModuleTest;
import java.util.Comparator;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Раздел «Уведомления» у встроенных ролей и список получателей мастерских сообщений ({@link StaffDirectory}).
 * Актор администратора — аккаунт, получивший {@code DEVELOPER} через bootstrap при регистрации.
 */
@ModuleTest
class NotificationsAccessTest extends IdentityTestSupport {

    @Autowired
    StaffDirectory staffDirectory;

    private SignedIn developer;
    private Actor admin;

    @BeforeEach
    void signInDeveloper() {
        developer = developer();
        admin = actor(developer);
    }

    @Test
    void masterAndDeveloperWriteNotifications() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);

        assertThat(role("MASTER").grants()).containsEntry(Section.NOTIFICATIONS, AccessLevel.WRITE);
        assertThat(role("DEVELOPER").grants()).containsEntry(Section.NOTIFICATIONS, AccessLevel.WRITE);
        assertThat(actor(staff).canWrite(Section.NOTIFICATIONS)).isTrue();
        assertThat(admin.canWrite(Section.NOTIFICATIONS)).isTrue();
        assertThat(access.account(staff.accountId(), admin).effective())
                .containsEntry(Section.NOTIFICATIONS, AccessLevel.WRITE);
    }

    @Test
    void staffDirectoryListsAccountsWithLevel() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);
        var client = signInFirstTime(profile("client", "+79211234568"));
        var blocked = signInFirstTime(profile("blocked", "+79211234569"));
        access.assignRole(blocked.accountId(), role("MASTER").id(), admin);
        clients.block(clientId(blocked), "грубит", master);
        var overridden = signInFirstTime(profile("overridden", "+79211234570"));
        access.assignRole(overridden.accountId(), role("MASTER").id(), admin);
        access.setOverride(overridden.accountId(), Section.BOOKINGS, AccessLevel.NONE, admin);
        var reader = signInFirstTime(profile("reader", "+79211234571"));
        access.setOverride(reader.accountId(), Section.BOOKINGS, AccessLevel.READ, admin);

        var bookings = staffDirectory.recipients(Section.BOOKINGS, AccessLevel.READ);

        assertThat(bookings)
                .contains(
                        new StaffRecipient(staff.accountId(), Set.of("MASTER")),
                        new StaffRecipient(developer.accountId(), Set.of("DEVELOPER")),
                        new StaffRecipient(reader.accountId(), Set.of()))
                .extracting(StaffRecipient::accountId)
                .doesNotContain(client.accountId(), blocked.accountId(), overridden.accountId());
        assertThat(bookings).isSortedAccordingTo(Comparator.comparing(StaffRecipient::accountId));

        // Порог уровня: у разработчика финансы только на чтение, у личного чтения записей нет изменения.
        assertThat(staffDirectory.recipients(Section.FINANCE, AccessLevel.WRITE))
                .extracting(StaffRecipient::accountId)
                .contains(staff.accountId())
                .doesNotContain(developer.accountId(), blocked.accountId());
        assertThat(staffDirectory.recipients(Section.BOOKINGS, AccessLevel.WRITE))
                .extracting(StaffRecipient::accountId)
                .contains(staff.accountId(), developer.accountId())
                .doesNotContain(reader.accountId());
    }
}
