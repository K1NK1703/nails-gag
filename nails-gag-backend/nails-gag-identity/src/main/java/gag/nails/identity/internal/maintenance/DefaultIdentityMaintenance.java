package gag.nails.identity.internal.maintenance;

import gag.nails.identity.IdentityMaintenance;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Обслуживание входа: три независимых шага, каждый в своей транзакции ({@link IdentityCleanUp}). Сбой
 * шага записывается в лог и не мешает остальным; следующий запуск попробует снова.
 */
@Service
class DefaultIdentityMaintenance implements IdentityMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DefaultIdentityMaintenance.class);

    private final IdentityCleanUp cleanUp;

    DefaultIdentityMaintenance(IdentityCleanUp cleanUp) {
        this.cleanUp = cleanUp;
    }

    @Override
    public int cleanUp() {
        var sessions = step("expired sessions", cleanUp::removeExpiredSessions);
        step("stale pending registrations", cleanUp::removeStalePendingRegistrations);
        step("old login events", cleanUp::removeOldLoginEvents);
        return sessions;
    }

    private static int step(String what, IntSupplier step) {
        try {
            var removed = step.getAsInt();
            if (removed > 0) {
                log.info("identity maintenance removed {} {}", removed, what);
            }
            return removed;
        } catch (RuntimeException e) {
            log.error("identity maintenance failed to remove {}", what, e);
            return 0;
        }
    }
}
