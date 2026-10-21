package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Заданный порядок двух транзакций без гонки: первая держит строку, вторая гарантированно встаёт на её блокировку до
 * фиксации первой. Ожидание проверяется по {@code pg_stat_activity}: другое соединение ждёт блокировку, а его запрос
 * касается нужной схемы — ожидания других тестов и таблиц не считаются.
 */
final class ConcurrencySupport {

    static final long WAIT_SECONDS = 20;

    private static final String LOCK_WAITS = """
            select count(*) from pg_stat_activity
            where datname = current_database() and pid <> pg_backend_pid()
              and wait_event_type = 'Lock' and query like :pattern""";

    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final String queryPattern;

    /** @param queryPattern шаблон LIKE для текста ждущего запроса, например {@code %clients.%} */
    ConcurrencySupport(TransactionTemplate transaction, JdbcClient jdbc, String queryPattern) {
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.queryPattern = queryPattern;
    }

    /**
     * {@code holder} в своей транзакции захватывает строку и ждёт, пока {@code contender} не встанет на её
     * блокировку; затем транзакция {@code holder} фиксируется. Возвращает исход {@code contender}.
     */
    Outcome whileRowIsHeld(Runnable holder, Runnable contender) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                holder.run();
                held.countDown();
                await(release);
            }));
            Future<Outcome> contending;
            try {
                assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                contending = executor.submit(() -> outcome(contender));
                awaitLockWait();
            } finally {
                release.countDown();
            }
            holding.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return contending.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Выполняет действие в другом потоке — значит, в своей транзакции — и дожидается её фиксации. */
    static void inAnotherTransaction(Runnable action) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(action).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    static Outcome outcome(Runnable attempt) {
        try {
            attempt.run();
            return new Outcome(null);
        } catch (RuntimeException e) {
            return new Outcome(e);
        }
    }

    private void awaitLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql(LOCK_WAITS)
                            .param("pattern", queryPattern)
                            .query(Integer.class)
                            .single()
                    > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no query matching " + queryPattern + " is waiting for a lock");
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

    record Outcome(@Nullable RuntimeException failure) {}
}
