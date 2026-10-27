package gag.nails.files;

import static gag.nails.files.TestImages.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import gag.nails.common.error.ValidationException;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * Одновременные привязка, удаление и уборка одного файла дают согласованный итог. Порядок задаётся защёлками и
 * ожиданием блокировки: одна транзакция держит строку, другая гарантированно встаёт на её блокировку до фиксации
 * первой.
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileConcurrencyTest extends FilesTestSupport {

    private static final long WAIT_SECONDS = 10;

    /** Другие соединения, ждущие блокировку строки таблицы файлов; ожидания других тестов и таблиц не считаются. */
    private static final String LOCK_WAITS = """
            select count(*) from pg_stat_activity
            where datname = current_database() and pid <> pg_backend_pid()
              and wait_event_type = 'Lock' and query like '%stored_file%'""";

    @Autowired
    FileMaintenance maintenance;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void attachWaitingForCommittedAttachIsRejected() throws Exception {
        var stored = store(png(64, 48), FilePurpose.PORTFOLIO);
        Runnable attach = () -> storage.attach(stored.id(), FilePurpose.PORTFOLIO, ACCOUNT);

        var second = whileRowIsHeld(attach, Completion.COMMIT, attach);

        assertRejected(second);
        assertThat(attachedAt(stored)).isNotNull();
    }

    @Test
    void attachWaitingForRolledBackAttachSucceeds() throws Exception {
        var stored = store(png(64, 48), FilePurpose.PORTFOLIO);
        Runnable attach = () -> storage.attach(stored.id(), FilePurpose.PORTFOLIO, ACCOUNT);

        var second = whileRowIsHeld(attach, Completion.ROLLBACK, attach);

        assertThat(second.succeeded()).isTrue();
        assertThat(attachedAt(stored)).isNotNull();
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
    void attachWaitingForCleanupIsRejected() throws Exception {
        var stored = store(png(64, 48), FilePurpose.REFERENCE);
        clock.advance(Duration.ofDays(2));

        var attach = whileRowIsHeld(
                () -> assertThat(maintenance.removeOrphans()).isEqualTo(1),
                Completion.COMMIT,
                () -> storage.attach(stored.id(), FilePurpose.REFERENCE, ACCOUNT));

        assertRejected(attach);
        assertThat(directory(stored)).doesNotExist();
        assertNothingStored();
    }

    @Test
    void concurrentDeletesOfOneFileAreQuiet() throws Exception {
        var stored = store(png(64, 48), FilePurpose.PORTFOLIO);

        var second =
                whileRowIsHeld(() -> storage.delete(stored.id()), Completion.COMMIT, () -> storage.delete(stored.id()));

        assertThat(second.succeeded()).isTrue();

        assertThat(directory(stored)).doesNotExist();
        assertNothingStored();
    }

    @Test
    void deleteWhileCleanupRemovesTheOrphanIsQuiet() throws Exception {
        var stored = store(png(64, 48), FilePurpose.REFERENCE);
        clock.advance(Duration.ofDays(2));

        var delete = whileRowIsHeld(
                () -> assertThat(maintenance.removeOrphans()).isEqualTo(1),
                Completion.COMMIT,
                () -> storage.delete(stored.id()));

        assertThat(delete.succeeded()).isTrue();

        assertThat(directory(stored)).doesNotExist();
        assertNothingStored();
    }

    /**
     * {@code holder} в своей транзакции захватывает строку и ждёт, пока {@code contender} не встанет на её блокировку;
     * затем транзакция {@code holder} фиксируется или откатывается. Возвращает исход {@code contender}.
     */
    private Outcome whileRowIsHeld(Runnable holder, Completion completion, Runnable contender) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                holder.run();
                held.countDown();
                await(release);
                if (completion == Completion.ROLLBACK) {
                    status.setRollbackOnly();
                }
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

    /**
     * Ждёт, пока другое соединение не встанет на блокировку строки таблицы файлов; ожидания других тестов и таблиц не
     * считаются.
     */
    private void awaitLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.sql(LOCK_WAITS).query(Integer.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no query is waiting for a lock on files.stored_file");
    }

    private @Nullable Instant attachedAt(StoredFileView file) {
        return jdbc.sql("select attached_at from files.stored_file where id = :id")
                .param("id", file.id())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    private static Outcome outcome(Runnable attempt) {
        try {
            attempt.run();
            return new Outcome(null);
        } catch (RuntimeException e) {
            return new Outcome(e);
        }
    }

    private static void assertRejected(Outcome outcome) {
        assertThat(outcome.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
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

    private enum Completion {
        COMMIT,
        ROLLBACK
    }

    private record Outcome(@Nullable RuntimeException failure) {

        boolean succeeded() {
            return failure == null;
        }
    }
}
