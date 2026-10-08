package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
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

/** Параллельные действия над клиентами с настоящими транзакциями дают согласованный итог. */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class ClientsConcurrencyTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

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
