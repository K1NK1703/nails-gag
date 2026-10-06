package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;

/** {@code bootstrap-developers} содержит {@code FAKE:bootstrap-developer} (см. {@link IdentityTestSupport}). */
@ModuleTest
class BootstrapTest extends IdentityTestSupport {

    @Test
    void bootstrapDeveloperGetsRoleOnRegistration() {
        var signedIn = developer();

        var actor = actor(signedIn);
        assertThat(actor.level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
        assertThat(actor.access()).containsAllEntriesOf(Actor.developerAccess());
        assertThat(actor.isStaff()).isTrue();
        var account = access.account(signedIn.accountId(), actor);
        assertThat(account.roles()).extracting(RoleView::code).containsExactly("DEVELOPER");
        assertThat(account.effective()).containsAllEntriesOf(Actor.developerAccess());
        assertThat(accessJournal()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(AccessChangeKind.BOOTSTRAP);
            assertThat(change.actorAccountId()).isNull();
            assertThat(change.targetAccountId()).isEqualTo(signedIn.accountId());
            assertThat(change.roleId()).isEqualTo(role("DEVELOPER").id());
        });

        signIn(new FakeProfile(BOOTSTRAP_SUBJECT, "+79990000001", null, "Разработчик", null));

        assertThat(access.account(signedIn.accountId(), actor).roles()).hasSize(1);
        assertThat(accessJournal()).hasSize(1);
    }

    @Test
    void ordinarySubjectGetsNoRole() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));

        assertThat(actor(signedIn).isStaff()).isFalse();
        assertThat(accessJournal()).isEmpty();
    }
}
