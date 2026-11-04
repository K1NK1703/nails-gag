package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.identity.model.SignedIn;
import gag.nails.testsupport.ModuleTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Раздел «Статистика посещений»: встроенные роли из базы и личное переопределение. Актор администратора —
 * аккаунт, получивший {@code DEVELOPER} через bootstrap при регистрации.
 */
@ModuleTest
class AnalyticsAccessTest extends IdentityTestSupport {

    private SignedIn developer;
    private Actor admin;

    @BeforeEach
    void signInDeveloper() {
        developer = developer();
        admin = actor(developer);
    }

    @Test
    void developerReadsAnalyticsByDefault() {
        assertThat(role("DEVELOPER").grants()).containsEntry(Section.ANALYTICS, AccessLevel.READ);
        assertThat(admin.level(Section.ANALYTICS)).isEqualTo(AccessLevel.READ);
        assertThat(access.account(developer.accountId(), admin).effective())
                .containsEntry(Section.ANALYTICS, AccessLevel.READ);
    }

    @Test
    void masterHasNoAnalyticsByDefault() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);

        assertThat(role("MASTER").grants()).doesNotContainKey(Section.ANALYTICS);
        assertThat(actor(staff).level(Section.ANALYTICS)).isEqualTo(AccessLevel.NONE);
        assertThat(actor(staff).canWrite(Section.BOOKINGS)).isTrue();
        assertThat(access.account(staff.accountId(), admin).effective())
                .containsEntry(Section.ANALYTICS, AccessLevel.NONE);
    }

    @Test
    void overrideGrantsAnalyticsToMaster() {
        var staff = signInFirstTime(profile("staff", "+79211234567"));
        access.assignRole(staff.accountId(), role("MASTER").id(), admin);

        var view = access.setOverride(staff.accountId(), Section.ANALYTICS, AccessLevel.READ, admin);

        assertThat(view.overrides()).containsExactly(Map.entry(Section.ANALYTICS, AccessLevel.READ));
        assertThat(view.effective()).containsEntry(Section.ANALYTICS, AccessLevel.READ);
        assertThat(actor(staff).canRead(Section.ANALYTICS)).isTrue();
        assertThat(actor(staff).canWrite(Section.ANALYTICS)).isFalse();

        access.setOverride(staff.accountId(), Section.ANALYTICS, null, admin);

        assertThat(actor(staff).level(Section.ANALYTICS)).isEqualTo(AccessLevel.NONE);
    }
}
