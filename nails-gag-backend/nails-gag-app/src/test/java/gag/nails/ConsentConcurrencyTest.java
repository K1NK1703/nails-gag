package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.event.ConsentGiven;
import gag.nails.clients.event.ConsentWithdrawn;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.Actor;
import gag.nails.common.event.DomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Запись согласия, его отзыв, объединение и обезличивание карточки сериализуются блокировкой строки карточки:
 * подписчики событий согласий видят их в порядке фиксации. Порядок задаётся не гонкой, а ожиданием блокировки: первая
 * транзакция держит карточку, вторая гарантированно встаёт на её блокировку до фиксации первой.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import({PostgresTestcontainers.class, ConsentConcurrencyTest.Recording.class})
class ConsentConcurrencyTest {

    private static final long WAIT_SECONDS = 20;
    private static final String CURRENT_VERSION = "2026-09-25";
    private static final ConsentKind PHOTO = ConsentKind.PHOTO_PUBLICATION;
    private static final String GIVEN = "given";
    private static final String WITHDRAWN = "withdrawn";

    /** Другие соединения, ждущие блокировку строки карточки клиента; ожидания других таблиц не считаются. */
    private static final String LOCK_WAITS = """
            select count(*) from pg_stat_activity
            where datname = current_database() and pid <> pg_backend_pid()
              and wait_event_type = 'Lock' and query like '%clients.client %'""";

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConsentEvents events;

    private final Actor master = Actor.master();

    @Test
    void consentRecordedWhileWithdrawalIsPendingStaysCurrent() throws Exception {
        var card = card("Мария");
        give(card);

        var recorded = whileCardIsHeld(() -> withdraw(card), () -> give(card));

        assertThat(recorded.failure()).isNull();
        assertThat(queries.hasCurrentConsents(card, Set.of(PHOTO))).isTrue();
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN, GIVEN);
    }

    @Test
    void withdrawalWaitingForRecordedConsentWithdrawsIt() throws Exception {
        var card = card("Мария");

        var withdrawn = whileCardIsHeld(() -> give(card), () -> withdraw(card));

        assertThat(withdrawn.failure()).isNull();
        assertThat(queries.hasCurrentConsents(card, Set.of(PHOTO))).isFalse();
        assertThat(queries.consentWithdrawals(card, master)).hasSize(1);
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN);
    }

    @Test
    void secondConcurrentWithdrawalChangesNothing() throws Exception {
        var card = card("Мария");
        give(card);

        var second = whileCardIsHeld(() -> withdraw(card), () -> withdraw(card));

        assertThat(second.failure()).isNull();
        assertThat(queries.consentWithdrawals(card, master)).hasSize(1);
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN);
    }

    @Test
    void withdrawalWaitingForMergeOfItsCardGetsClientNotFound() throws Exception {
        var card = card("Мария");
        var survivor = card("Аня");
        give(card);

        var withdrawn = whileCardIsHeld(() -> clients.merge(card, survivor, master), () -> withdraw(card));

        assertThat(withdrawn.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(queries.hasCurrentConsents(survivor, Set.of(PHOTO))).isTrue();
        assertThat(queries.consentWithdrawals(survivor, master)).isEmpty();
        assertThat(events.of(card)).containsExactly(GIVEN);
    }

    @Test
    void mergeWaitingForWithdrawalCarriesIt() throws Exception {
        var card = card("Мария");
        var survivor = card("Аня");
        give(card);

        var merged = whileCardIsHeld(() -> withdraw(card), () -> clients.merge(card, survivor, master));

        assertThat(merged.failure()).isNull();
        assertThat(queries.byId(card)).isEmpty();
        assertThat(queries.hasCurrentConsents(survivor, Set.of(PHOTO))).isFalse();
        assertThat(queries.consentWithdrawals(survivor, master)).hasSize(1);
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN);
    }

    @Test
    void consentRecordWaitingForMergeOfItsCardGetsClientNotFound() throws Exception {
        var card = card("Мария");
        var survivor = card("Аня");

        var recorded = whileCardIsHeld(() -> clients.merge(card, survivor, master), () -> give(card));

        assertThat(recorded.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(queries.consents(survivor, master)).isEmpty();
        assertThat(events.of(card)).isEmpty();
    }

    @Test
    void withdrawalWaitingForEraseIsRejected() throws Exception {
        var card = card("Мария");
        give(card);

        var withdrawn = whileCardIsHeld(() -> clients.erase(card), () -> withdraw(card));

        assertThat(withdrawn.failure()).isInstanceOf(ClientErasedException.class);
        assertThat(queries.consentWithdrawals(card, master)).isEmpty();
        assertThat(events.of(card)).containsExactly(GIVEN);
    }

    private UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), master)
                .id();
    }

    private void give(UUID clientId) {
        clients.recordConsent(
                clientId, new RecordConsent(PHOTO, CURRENT_VERSION, ConsentSource.ENTERED_BY_MASTER), master);
    }

    private void withdraw(UUID clientId) {
        clients.withdrawConsent(clientId, PHOTO, master);
    }

    /**
     * {@code holder} в своей транзакции захватывает карточку и ждёт, пока {@code contender} не встанет на её
     * блокировку; затем транзакция {@code holder} фиксируется. Возвращает исход {@code contender}.
     */
    private Outcome whileCardIsHeld(Runnable holder, Runnable contender) throws Exception {
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

    private void awaitLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql(LOCK_WAITS).query(Integer.class).single() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no query is waiting for a lock on clients.client");
    }

    private static Outcome outcome(Runnable attempt) {
        try {
            attempt.run();
            return new Outcome(null);
        } catch (RuntimeException e) {
            return new Outcome(e);
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

    private record Outcome(@Nullable RuntimeException failure) {}

    /** События согласий всех потоков в порядке публикации — как их увидит синхронный подписчик. */
    static final class ConsentEvents {

        private final Queue<DomainEvent> published = new ConcurrentLinkedQueue<>();

        @EventListener
        void on(ConsentGiven event) {
            published.add(event);
        }

        @EventListener
        void on(ConsentWithdrawn event) {
            published.add(event);
        }

        /** Метки {@link #GIVEN} и {@link #WITHDRAWN} событий клиента: проверка не зависит от времени событий. */
        List<String> of(UUID clientId) {
            var marks = new ArrayList<String>();
            for (var event : published) {
                if (event instanceof ConsentGiven given && given.clientId().equals(clientId)) {
                    marks.add(GIVEN);
                } else if (event instanceof ConsentWithdrawn withdrawn
                        && withdrawn.clientId().equals(clientId)) {
                    marks.add(WITHDRAWN);
                }
            }
            return marks;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Recording {

        @Bean
        ConsentEvents consentEvents() {
            return new ConsentEvents();
        }
    }
}
