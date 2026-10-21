package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Параллельные действия над клиентами с настоящими транзакциями дают согласованный итог. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class ClientsConcurrencyTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.master();

    @Test
    void twoLoginsConfirmingOnePhoneAtOnceGiveItToExactlyOneCard() throws Exception {
        // Свой номер на каждый прогон: база одна на все тесты модуля, а телефон уникален.
        var phone = "+7999" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        var anna = card("Анна");
        var maria = card("Мария");

        var outcomes = race(() -> clients.confirmPhone(anna, phone), () -> clients.confirmPhone(maria, phone));

        // Проверка «номер свободен» у обоих проходит до записи; уникальный индекс пропускает одного.
        // Второй либо упёрся в индекс (PhoneTakenException), либо увидел уже зафиксированный номер (false).
        assertThat(outcomes)
                .filteredOn(outcome -> Boolean.TRUE.equals(outcome.result()))
                .hasSize(1);
        var loser = outcomes.stream()
                .filter(outcome -> !Boolean.TRUE.equals(outcome.result()))
                .findFirst()
                .orElseThrow();
        if (loser.failure() != null) {
            assertThat(loser.failure()).isInstanceOf(PhoneTakenException.class);
        } else {
            assertThat(loser.result()).isEqualTo(false);
        }
        var owner = queries.byPhone(phone).map(ClientView::id).orElseThrow();
        assertThat(owner).isIn(anna, maria);
        var other = owner.equals(anna) ? maria : anna;
        assertThat(queries.get(owner, master).phone()).isEqualTo(phone);
        assertThat(queries.get(other, master).phone()).isNull();
    }

    /**
     * Карточка прочитана в транзакции до блокировки строки, а другая транзакция тем временем заблокировала клиента
     * и зафиксировалась: объединение под блокировкой карточки перечитывает её и видит блокировку, а не прочитанное
     * раньше.
     */
    @Test
    void mergeOfOwnCardBlockedAfterItWasReadIsRejected() throws Exception {
        var ownAccount = UUID.randomUUID();
        var otherAccount = UUID.randomUUID();
        var own = clients.register(new RegisterClient(ownAccount, "Мария", null, null))
                .id();
        var other = clients.register(new RegisterClient(otherAccount, "Мария", null, null))
                .id();

        var failure = transaction.execute(status -> {
            assertThat(queries.byAccountId(otherAccount))
                    .hasValueSatisfying(card -> assertThat(card.blocked()).isFalse());
            ConcurrencySupport.inAnotherTransaction(() -> clients.block(other, null, master));
            status.setRollbackOnly();
            try {
                clients.mergeOwn(other, Actor.client(own));
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });

        assertThat(failure).isInstanceOf(ClientBlockedException.class);
        assertThat(queries.get(other, master).blocked()).isTrue();
        assertThat(queries.byId(own)).isPresent();
    }

    /**
     * Объединение держит удаляемую карточку; ручная смена признака предоплаты по ней ждёт блокировку карточки и
     * после фиксации объединения не находит её — без взаимной блокировки и без нарушения внешнего ключа журнала.
     */
    @Test
    void prepaymentChangeWaitingForMergeOfItsCardGetsClientNotFound() throws Exception {
        var card = card("Мария");
        var survivor = card("Аня");

        var changed = rows().whileRowIsHeld(
                        () -> clients.merge(card, survivor, master),
                        () -> clients.setPrepaymentRequired(card, true, null, master));

        assertThat(changed.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(queries.byId(card)).isEmpty();
        assertThat(queries.get(survivor, master).prepaymentRequired()).isFalse();
    }

    /**
     * Изменение оставшейся карточки ждёт объединения и применяется к уже объединённой: перенесённые телефон и заметка
     * мастера не затираются прочитанным до объединения состоянием.
     */
    @Test
    void updateWaitingForMergeKeepsWhatMergeCarried() throws Exception {
        var phone = "+7999" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        var card = clients.create(new CreateClient("Мария", null, null, phone, null, null), master)
                .id();
        clients.updateMasterNote(card, "любит квадрат", master);
        var survivor = card("Аня");

        var updated = rows().whileRowIsHeld(
                        () -> clients.merge(card, survivor, master),
                        () -> clients.update(survivor, new UpdateClient("Анна", "Иванова", null, null, null), master));

        assertThat(updated.failure()).isNull();
        var merged = queries.get(survivor, master);
        assertThat(merged.firstName()).isEqualTo("Анна");
        assertThat(merged.lastName()).isEqualTo("Иванова");
        assertThat(merged.phone()).isEqualTo(phone);
        assertThat(queries.masterNote(survivor, master)).contains("любит квадрат");
    }

    private ConcurrencySupport rows() {
        return new ConcurrencySupport(transaction, jdbc, "%clients.%");
    }

    private UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), master)
                .id();
    }

    /** Запускает попытки одновременно и возвращает исход каждой. */
    private static List<Outcome> race(Callable<?>... attempts) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts.length)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (var attempt : attempts) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return new Outcome(attempt.call(), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private record Outcome(
            @Nullable Object result, @Nullable Exception failure) {}
}
