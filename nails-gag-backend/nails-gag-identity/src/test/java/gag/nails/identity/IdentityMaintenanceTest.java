package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class IdentityMaintenanceTest extends IdentityTestSupport {

    @Autowired
    IdentityMaintenance maintenance;

    @Test
    void expiredSessionsAreRemovedAndLiveOnesKept() {
        // Открыта в первый день и используется каждые 20 дней: к 91-му дню истекает срок от входа.
        var longLived = signInFirstTime(profile("u1", "+79211234567"));
        // Открыта на 50-й день и больше не используется: к 91-му дню 41 день без активности.
        clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(50)));
        var idle = signInFirstTime(profile("u2", "+79215550000"));
        // Открыта на 80-й день: к 91-му дню живая по обоим срокам.
        clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(80)));
        var live = signInFirstTime(profile("u3", "+79217770000"));
        for (var day = 20; day <= 80; day += 20) {
            clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(day)));
            assertThat(sessions.authenticate(longLived.sessionToken())).isPresent();
        }
        clock.set(TestTimeConfiguration.START.plus(Duration.ofDays(91)));

        assertThat(maintenance.cleanUp()).isEqualTo(2);

        assertThat(sessions.authenticate(longLived.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(idle.sessionToken())).isEmpty();
        assertThat(sessions.authenticate(live.sessionToken())).isPresent();
        assertThat(maintenance.cleanUp()).isZero();
    }

    @Test
    void stalePendingRegistrationsAreRemoved() {
        var stale = registrationRequired(profile("u1", "+79211234567"));
        clock.advance(Duration.ofMinutes(31));
        var fresh = registrationRequired(profile("u2", "+79215550000"));

        maintenance.cleanUp();

        var signedIn = loginFlow.completeRegistration(
                fresh.ticket(), new CompleteRegistration("Оля", "+79215550000", POLICY_VERSION), CLIENT);
        assertThat(sessions.authenticate(signedIn.sessionToken())).isPresent();
        // Часы назад: по сроку билет ещё годился бы, но его уже нет.
        clock.set(TestTimeConfiguration.START.plus(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> loginFlow.completeRegistration(
                        stale.ticket(), new CompleteRegistration("Анна", "+79211234567", POLICY_VERSION), CLIENT))
                .isInstanceOf(RegistrationExpiredException.class);
    }

    @Test
    void oldLoginEventsAreRemoved() {
        var user = profile("u1", "+79211234567");
        signInFirstTime(user);
        clock.advance(Duration.ofDays(89));
        signIn(user);
        clock.advance(Duration.ofDays(2));
        var recent = signIn(user);
        assertThat(loginJournal()).hasSize(3);

        maintenance.cleanUp();

        assertThat(loginJournal()).hasSize(2).allSatisfy(event -> {
            assertThat(event.outcome()).isEqualTo(LoginOutcome.SIGNED_IN);
            assertThat(event.accountId()).isEqualTo(recent.accountId());
        });
    }
}
