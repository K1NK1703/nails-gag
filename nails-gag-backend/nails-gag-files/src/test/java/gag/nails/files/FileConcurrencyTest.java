package gag.nails.files;

import static gag.nails.files.TestImages.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import gag.nails.common.error.ValidationException;
import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Одновременные привязка, удаление и уборка одного файла дают согласованный итог. Порядок задаётся защёлками: одна
 * транзакция держит строку, другая начинается, пока первая не зафиксирована.
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileConcurrencyTest extends FilesTestSupport {

    private static final long WAIT_SECONDS = 10;

    @Autowired
    FileMaintenance maintenance;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void twoAttachesOfOneFileLetExactlyOneThrough() throws Exception {
        var stored = store(png(64, 48), FilePurpose.PORTFOLIO);

        Runnable attach = () -> storage.attach(stored.id(), FilePurpose.PORTFOLIO, ACCOUNT);

        var outcomes = race(attach, attach);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::failure)
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void cleanupSkipsOrphanBeingAttached() throws Exception {
        var stored = store(png(64, 48), FilePurpose.REFERENCE);
        var attached = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var attaching = executor.submit(() -> transaction.executeWithoutResult(status -> {
                storage.attach(stored.id(), FilePurpose.REFERENCE, ACCOUNT);
                attached.countDown();
                await(release);
            }));
            try {
                assertThat(attached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofDays(2));
                // Строку держит незафиксированная привязка: уборка её пропускает, а не ждёт.
                var removed = assertTimeoutPreemptively(Duration.ofSeconds(5), maintenance::removeOrphans);
                assertThat(removed).isZero();
            } finally {
                release.countDown();
            }
            attaching.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(maintenance.removeOrphans()).isZero();
        assertThat(storage.find(stored.id())).isPresent();
        assertThat(directory(stored)).isDirectory();
    }

    @Test
    void attachAfterCleanupIsRejected() {
        var stored = store(png(64, 48), FilePurpose.REFERENCE);
        clock.advance(Duration.ofDays(2));

        assertThat(maintenance.removeOrphans()).isEqualTo(1);

        assertThatThrownBy(() -> storage.attach(stored.id(), FilePurpose.REFERENCE, ACCOUNT))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }

    @Test
    void concurrentDeletesOfOneFileAreQuiet() throws Exception {
        var stored = store(png(64, 48), FilePurpose.PORTFOLIO);

        whileRowIsHeld(() -> storage.delete(stored.id()), () -> storage.delete(stored.id()));

        assertThat(directory(stored)).doesNotExist();
        assertNothingStored();
    }

    @Test
    void deleteWhileCleanupRemovesTheOrphanIsQuiet() throws Exception {
        var stored = store(png(64, 48), FilePurpose.REFERENCE);
        clock.advance(Duration.ofDays(2));

        whileRowIsHeld(() -> assertThat(maintenance.removeOrphans()).isEqualTo(1), () -> storage.delete(stored.id()));

        assertThat(directory(stored)).doesNotExist();
        assertNothingStored();
    }

    /**
     * {@code holder} в своей транзакции захватывает строку и ждёт, пока {@code contender} не встанет на её блокировку;
     * затем {@code holder} фиксируется. {@code contender} должен завершиться без ошибки.
     */
    private void whileRowIsHeld(Runnable holder, Runnable contender) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                holder.run();
                held.countDown();
                await(release);
            }));
            Future<?> contending;
            try {
                assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                contending = executor.submit(contender);
                awaitLockWait();
            } finally {
                release.countDown();
            }
            holding.get(WAIT_SECONDS, TimeUnit.SECONDS);
            contending.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Ждёт, пока какой-нибудь запрос этой базы не встанет на блокировку строки. */
    private void awaitLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.sql("select count(*) from pg_stat_activity"
                            + " where datname = current_database() and wait_event_type = 'Lock'")
                    .query(Integer.class)
                    .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no query is waiting for a lock");
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

    private static List<Outcome> race(Runnable... attempts) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts.length)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (var attempt : attempts) {
                futures.add(executor.submit((Callable<Outcome>) () -> {
                    start.await();
                    try {
                        attempt.run();
                        return new Outcome(null);
                    } catch (RuntimeException e) {
                        return new Outcome(e);
                    }
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(WAIT_SECONDS * 3, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private record Outcome(@Nullable RuntimeException failure) {

        boolean succeeded() {
            return failure == null;
        }
    }
}
