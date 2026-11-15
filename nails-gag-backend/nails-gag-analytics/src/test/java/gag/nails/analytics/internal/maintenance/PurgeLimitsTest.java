package gag.nails.analytics.internal.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.common.time.SalonTimeZone;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Пределы транзакции уборки: чужая долгая блокировка таблицы её не вешает. Уборка здесь — своя, с короткими
 * пределами (сотни миллисекунд вместо секунд) и часами в 1980 году: если пределы сломаются и удаление всё же пройдёт,
 * оно не заденет ничьих дней. Блокировку держит отдельная транзакция до конца проверки.
 */
@AnalyticsModuleTest
class PurgeLimitsTest {

    private static final Duration SHORT = Duration.ofMillis(200);
    private static final Duration LONG = Duration.ofSeconds(10);
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final long WAIT_SECONDS = 20;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("1980-01-01T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    SalonTimeZone salonTimeZone;

    @Autowired
    JdbcClient jdbc;

    @Test
    void lockWaitIsLimited() throws Exception {
        var maintenance = maintenance(SHORT, LONG);

        whileVisitorTableIsLocked(() -> assertTimeoutPreemptively(
                DEADLINE,
                () -> assertThatThrownBy(maintenance::purgeExpired).rootCause().hasMessageContaining("lock timeout")));
    }

    /** Ожидание блокировки дольше {@code statement_timeout} — оператор прерывается по нему. */
    @Test
    void statementTimeIsLimited() throws Exception {
        var maintenance = maintenance(LONG, SHORT);

        whileVisitorTableIsLocked(() -> assertTimeoutPreemptively(
                DEADLINE,
                () -> assertThatThrownBy(maintenance::purgeExpired)
                        .rootCause()
                        .hasMessageContaining("statement timeout")));
    }

    private DefaultAnalyticsMaintenance maintenance(Duration lockTimeout, Duration statementTimeout) {
        return new DefaultAnalyticsMaintenance(
                entityManager, transaction, salonTimeZone, CLOCK, lockTimeout, statementTimeout);
    }

    private void whileVisitorTableIsLocked(Runnable check) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("lock table analytics.visitor in access exclusive mode")
                        .update();
                held.countDown();
                await(release);
            }));
            try {
                assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                check.run();
            } finally {
                release.countDown();
            }
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
