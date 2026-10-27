package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.DocumentVersionService;
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
import org.junit.jupiter.api.BeforeEach;
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
 * подписчики событий согласий видят их в порядке фиксации. Порядок задаётся не гонкой, а ожиданием блокировки
 * ({@link ConcurrencySupport#whileRowIsHeld}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import({PostgresTestcontainers.class, ConsentConcurrencyTest.Recording.class})
class ConsentConcurrencyTest {

    private static final ConsentKind PHOTO = ConsentKind.PHOTO_PUBLICATION;
    private static final String GIVEN = "given";
    private static final String WITHDRAWN = "withdrawn";

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DocumentVersionService documents;

    @Autowired
    ConsentEvents events;

    private final Actor master = Actor.master();
    private ConcurrencySupport rows;

    @BeforeEach
    void rowLocks() {
        rows = new ConcurrencySupport(transaction, jdbc, "%clients.%");
    }

    @Test
    void consentRecordedWhileWithdrawalIsPendingStaysCurrent() throws Exception {
        var card = card("Мария");
        give(card);

        var recorded = rows.whileRowIsHeld(() -> withdraw(card), () -> give(card));

        assertThat(recorded.failure()).isNull();
        assertThat(queries.hasCurrentConsents(card, Set.of(PHOTO))).isTrue();
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN, GIVEN);
    }

    @Test
    void withdrawalWaitingForRecordedConsentWithdrawsIt() throws Exception {
        var card = card("Мария");

        var withdrawn = rows.whileRowIsHeld(() -> give(card), () -> withdraw(card));

        assertThat(withdrawn.failure()).isNull();
        assertThat(queries.hasCurrentConsents(card, Set.of(PHOTO))).isFalse();
        assertThat(queries.consentWithdrawals(card, master)).hasSize(1);
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN);
    }

    @Test
    void secondConcurrentWithdrawalChangesNothing() throws Exception {
        var card = card("Мария");
        give(card);

        var second = rows.whileRowIsHeld(() -> withdraw(card), () -> withdraw(card));

        assertThat(second.failure()).isNull();
        assertThat(queries.consentWithdrawals(card, master)).hasSize(1);
        assertThat(events.of(card)).containsExactly(GIVEN, WITHDRAWN);
    }

    @Test
    void withdrawalWaitingForMergeOfItsCardGetsClientNotFound() throws Exception {
        var card = card("Мария");
        var survivor = card("Аня");
        give(card);

        var withdrawn = rows.whileRowIsHeld(() -> clients.merge(card, survivor, master), () -> withdraw(card));

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

        var merged = rows.whileRowIsHeld(() -> withdraw(card), () -> clients.merge(card, survivor, master));

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

        var recorded = rows.whileRowIsHeld(() -> clients.merge(card, survivor, master), () -> give(card));

        assertThat(recorded.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(queries.consents(survivor, master)).isEmpty();
        assertThat(events.of(card)).isEmpty();
    }

    @Test
    void withdrawalWaitingForEraseIsRejected() throws Exception {
        var card = card("Мария");
        give(card);

        var withdrawn = rows.whileRowIsHeld(() -> clients.erase(card), () -> withdraw(card));

        assertThat(withdrawn.failure()).isInstanceOf(ClientErasedException.class);
        assertThat(queries.consentWithdrawals(card, master)).isEmpty();
        assertThat(events.of(card)).containsExactly(GIVEN);
    }

    @Test
    void concurrentRecordsBothCount() throws Exception {
        var card = card("Мария");

        var second = rows.whileRowIsHeld(() -> give(card), () -> give(card));

        assertThat(second.failure()).isNull();
        assertThat(queries.consents(card, master)).hasSize(2);
        assertThat(events.of(card)).containsExactly(GIVEN, GIVEN);
    }

    /**
     * Карточка прочитана в транзакции до блокировки строки, а другая транзакция тем временем обезличила её и
     * зафиксировалась: запись согласия под блокировкой перечитывает карточку и видит обезличивание.
     */
    @Test
    void consentRecordForCardErasedAfterItWasReadIsRejected() {
        var card = card("Мария");

        var failure = transaction.execute(status -> {
            assertThat(queries.byId(card))
                    .hasValueSatisfying(read -> assertThat(read.erased()).isFalse());
            ConcurrencySupport.inAnotherTransaction(() -> clients.erase(card));
            status.setRollbackOnly();
            return ConcurrencySupport.outcome(() -> give(card)).failure();
        });

        assertThat(failure).isInstanceOf(ClientErasedException.class);
        assertThat(queries.consents(card, master)).isEmpty();
        assertThat(events.of(card)).isEmpty();
    }

    private UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), master)
                .id();
    }

    private void give(UUID clientId) {
        clients.recordConsent(
                clientId,
                new RecordConsent(
                        PHOTO, documents.current(PHOTO.document()).version(), ConsentSource.ENTERED_BY_MASTER),
                master);
    }

    private void withdraw(UUID clientId) {
        clients.withdrawConsent(clientId, PHOTO, master);
    }

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
