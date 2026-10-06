package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@ModuleTest
class SessionsTest extends IdentityTestSupport {

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void unknownTokenIsAnonymous() {
        assertThat(sessions.authenticate("no-such-token")).isEmpty();
        sessions.close("no-such-token");
    }

    @Test
    void closedTokenIsAnonymous() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();

        sessions.close(signedIn.sessionToken());

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
    }

    @Test
    void closeAllClosesEverySession() {
        var first = signInFirstTime(profile("u1", "+79211234567"));
        var second = signIn(profile("u1", "+79211234567"));
        var stranger = signInFirstTime(profile("u2", "+79215550000"));

        sessions.closeAll(actor(first));

        assertThat(sessions.authenticate(first.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(second.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(stranger.sessionToken())).isPresent();
        assertThatThrownBy(() -> sessions.closeAll(Actor.client(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void clientSessionExpiresAfter30IdleDays() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));

        clock.advance(Duration.ofDays(29));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();

        clock.advance(Duration.ofDays(30).plusSeconds(1));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
    }

    @Test
    void staffSessionExpiresAfter14IdleDays() {
        var developer = developer();
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        access.assignRole(signedIn.accountId(), role("MASTER").id(), actor(developer));

        clock.advance(Duration.ofDays(13));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();

        clock.advance(Duration.ofDays(14).plusSeconds(1));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
    }

    @Test
    void sessionExpiresAfter90DaysRegardlessOfActivity() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));

        for (var day = 20; day <= 80; day += 20) {
            clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(day)));
            assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();
        }

        clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(91)));
        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
    }

    @Test
    void lastUsedIsUpdatedAtMostEveryFiveMinutes() {
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        assertThat(lastUsedAt(signedIn)).isEqualTo(TestTimeConfiguration.START);

        clock.advance(Duration.ofMinutes(4));
        sessions.authenticate(signedIn.sessionToken());
        sessions.authenticate(signedIn.sessionToken());
        assertThat(lastUsedAt(signedIn)).isEqualTo(TestTimeConfiguration.START);

        clock.advance(Duration.ofMinutes(2));
        sessions.authenticate(signedIn.sessionToken());
        assertThat(lastUsedAt(signedIn)).isEqualTo(TestTimeConfiguration.START.plus(Duration.ofMinutes(6)));
    }

    @Test
    void blockedClientSessionIsDroppedOnNextRequest() {
        developer();
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        var clientId = clientId(signedIn);

        clients.block(clientId, "грубит", master);

        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();

        clients.unblock(clientId, master);
        assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        assertThat(signIn(profile("u1", "+79211234567")).accountId()).isEqualTo(signedIn.accountId());
    }

    @Test
    void actorCarriesEffectiveAccessImmediately() {
        var developer = developer();
        var signedIn = signInFirstTime(profile("u1", "+79211234567"));
        assertThat(actor(signedIn).level(Section.SCHEDULE)).isEqualTo(AccessLevel.NONE);

        access.assignRole(signedIn.accountId(), role("MASTER").id(), actor(developer));

        var authenticated = sessions.authenticate(signedIn.sessionToken()).orElseThrow();
        assertThat(authenticated.actor().level(Section.SCHEDULE)).isEqualTo(AccessLevel.WRITE);
        assertThat(authenticated.actor().level(Section.ACCESS)).isEqualTo(AccessLevel.NONE);
        assertThat(authenticated.account().access()).containsAllEntriesOf(Actor.masterAccess());

        access.revokeRole(signedIn.accountId(), role("MASTER").id(), actor(developer));

        assertThat(actor(signedIn).level(Section.SCHEDULE)).isEqualTo(AccessLevel.NONE);
        assertThat(actor(signedIn).isStaff()).isFalse();
    }

    /** {@code last_used_at} сессии наружу не отдаётся: читается прямо из таблицы, как сид в {@code IdentitySeedTest}. */
    private Instant lastUsedAt(SignedIn signedIn) {
        entityManager.flush();
        return jdbc.sql("select last_used_at from identity.session where account_id = :accountId")
                .param("accountId", signedIn.accountId())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
}
