package gag.nails.identity.internal.maintenance;

import gag.nails.identity.internal.login.LoginEventRepository;
import gag.nails.identity.internal.login.PendingRegistrationRepository;
import gag.nails.identity.internal.login.PendingRegistrations;
import gag.nails.identity.internal.session.DefaultSessions;
import gag.nails.identity.internal.session.SessionRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Один шаг обслуживания — в своей транзакции, как {@code BookingTimeouts} в записях: сбой шага
 * откатывает только его, остальные шаги {@code cleanUp} идут дальше.
 *
 * <p>Сессии удаляются по срокам из спецификации 4.5: 90 дней от входа или 30 без активности. Срок без
 * активности у сотрудников короче (14 дней), но его проверяет {@code authenticate} при следующем запросе;
 * обслуживание чистит по общему сроку, чтобы не собирать права каждого аккаунта.
 */
@Component
@Transactional
class IdentityCleanUp {

    /** Журнал входов хранится столько же, сколько живёт сессия от входа. */
    static final Duration LOGIN_EVENT_RETENTION = Duration.ofDays(90);

    private final SessionRepository sessions;
    private final PendingRegistrationRepository pendings;
    private final LoginEventRepository events;
    private final Clock clock;

    IdentityCleanUp(
            SessionRepository sessions,
            PendingRegistrationRepository pendings,
            LoginEventRepository events,
            Clock clock) {
        this.sessions = sessions;
        this.pendings = pendings;
        this.events = events;
        this.clock = clock;
    }

    int removeExpiredSessions() {
        var now = clock.instant();
        return sessions.deleteExpired(
                now.minus(DefaultSessions.ABSOLUTE_LIFETIME), now.minus(DefaultSessions.CLIENT_IDLE_LIFETIME));
    }

    int removeStalePendingRegistrations() {
        return pendings.deleteCreatedBefore(clock.instant().minus(PendingRegistrations.TTL));
    }

    int removeOldLoginEvents() {
        return events.deleteOccurredBefore(clock.instant().minus(LOGIN_EVENT_RETENTION));
    }
}
