package gag.nails.analytics.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.analytics.model.Metric;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Предел времени запросов отчётов доходит до драйвера: таблицу счётчиков держит исключительная блокировка другой
 * транзакции (как у {@code ALTER TABLE}), и запрос, вставший за ней, отменяется по пределу, а не ждёт. Предел в тесте —
 * секунда (свой экземпляр {@link PeriodCounts}): драйвер считает его в целых секундах. Блокировка держится только на
 * время теста; контейнер базы у контекста тестов модуля свой, и тесты модуля идут по очереди.
 */
@AnalyticsModuleTest
class QueryTimeoutTest {

    private static final LocalDate DAY = LocalDate.of(2314, 9, 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** SQLSTATE {@code query_canceled}. */
    private static final String QUERY_CANCELED = "57014";

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void queriesAreCancelledAfterTheTimeout() throws Exception {
        var counts = new PeriodCounts(entityManager, TIMEOUT);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lock = CompletableFuture.runAsync(
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    entityManager
                            .createNativeQuery("lock table analytics.daily_count in access exclusive mode")
                            .executeUpdate();
                    locked.countDown();
                    await(release);
                }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertCancelledInTime(() -> read(() -> counts.keySums(DAY, DAY, List.of(Metric.PAGE_VIEW), 100)));
            assertCancelledInTime(() -> read(() -> counts.daySums(DAY, DAY, List.of(Metric.PAGE_VIEW))));
        } finally {
            release.countDown();
            lock.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Отменён по пределу — не раньше него и задолго до того, как блокировка ушла бы сама. Драйвер отменяет запрос
     * запросом отмены PostgreSQL ({@value #QUERY_CANCELED}), а Hibernate отдаёт это общей
     * {@link PersistenceException}, не {@code QueryTimeoutException}.
     */
    private static void assertCancelledInTime(ThrowingCallable call) {
        var started = System.nanoTime();
        assertThatThrownBy(call)
                .isInstanceOf(PersistenceException.class)
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(QUERY_CANCELED));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(TIMEOUT, Duration.ofSeconds(5));
    }

    private void read(Runnable query) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> query.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while holding the lock", e);
        }
    }
}
