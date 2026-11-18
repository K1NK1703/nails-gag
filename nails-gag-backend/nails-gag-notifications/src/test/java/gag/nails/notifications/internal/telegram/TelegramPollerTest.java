package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import gag.nails.testsupport.MutableClock;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Приём обновлений бота с настоящим клиентом Bot API и сервером-заглушкой: смещение, паузы после сбоев, конфликт
 * второго приёмщика, сбой обработчика, быстрая остановка посреди долгого запроса и паузы. Паузы между попытками в
 * большинстве тестов не ждут, а записываются.
 */
@ExtendWith(OutputCaptureExtension.class)
class TelegramPollerTest {

    private static final String TOKEN = "987654:poller-secret";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(50);
    private static final Duration LONG = Duration.ofSeconds(30);

    private final TelegramStub stub = TelegramStub.started();
    private final TelegramApi api = TelegramApi.connect(
            stub.url(),
            TOKEN,
            new TelegramApi.Timeouts(
                    Duration.ofSeconds(2), Duration.ofSeconds(2), LONG.plusSeconds(10), Duration.ofSeconds(2)));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC);
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private final List<Long> handled = new CopyOnWriteArrayList<>();
    private Consumer<Update> handler = update -> handled.add(update.updateId());
    private TelegramPoller poller = poller(pauses::add);

    @AfterEach
    void stopAll() {
        poller.stop();
        stub.close();
        api.close();
    }

    @Test
    void offsetMovesPastEveryHandledUpdate() {
        stub.respond(
                "getUpdates",
                Reply.updates("[" + update(10, "/start") + ", " + update(11, "привет") + "]"),
                Reply.updates("[" + update(12, "/stop") + "]"),
                Reply.hang(LONG));

        poller.start();
        var requests = stub.await("getUpdates", 3);

        assertThat(handled).containsExactly(10L, 11L, 12L);
        assertThat(requests)
                .extracting(request -> request.body().path("offset").asLong(-1))
                .containsExactly(-1L, 12L, 13L);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.token()).isEqualTo(TOKEN);
            assertThat(request.body().path("timeout").asInt()).isEqualTo(50);
            assertThat(request.body().path("allowed_updates").toString()).isEqualTo("[\"message\"]");
        });
        assertThat(pauses).isEmpty();
    }

    @Test
    void failuresPauseLongerAndLongerUpToAMinute() {
        stub.respond(
                "getUpdates",
                Reply.error(500, "Internal Server Error", null),
                Reply.html(502),
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.updates("[" + update(5, "привет") + "]"),
                Reply.error(500, "Internal Server Error", null),
                Reply.hang(LONG));

        poller.start();
        stub.await("getUpdates", 8);

        assertThat(pauses)
                .containsExactly(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(5));
        assertThat(handled).containsExactly(5L);
    }

    @Test
    void secondPollerOrWebhookIsReportedAsConflict(CapturedOutput output) {
        stub.respond(
                "getUpdates",
                Reply.error(409, "Conflict: terminated by other getUpdates request", null),
                Reply.hang(LONG));

        poller.start();
        stub.await("getUpdates", 2);

        assertThat(pauses).containsExactly(Duration.ofSeconds(5));
        assertThat(output.getOut())
                .contains("telegram getUpdates conflict (HTTP 409): another poller uses this bot token or a webhook"
                        + " is set");
    }

    /** Три сбоя за минуту — одна строка; четвёртый, через минуту, называет два пропущенных. */
    @Test
    void failuresAreLoggedAtMostOncePerMinute(CapturedOutput output) {
        stub.respond(
                "getUpdates",
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.error(502, "Bad Gateway", null),
                Reply.hang(LONG));
        poller = poller(pause -> {
            pauses.add(pause);
            if (pauses.size() == 3) {
                clock.advance(Duration.ofMinutes(1));
            }
        });

        poller.start();
        stub.await("getUpdates", 5);
        await(() -> pauses.size() == 4);

        var warnings = output.getOut()
                .lines()
                .filter(line -> line.contains("telegram polling failed"))
                .toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.getFirst())
                .contains("telegram getUpdates failed: HTTP 500; 0 more failures since the previous warning");
        assertThat(warnings.get(1))
                .contains("telegram getUpdates failed: HTTP 502; 2 more failures since the previous warning");
    }

    /** Первый 409 подряд пишется всегда, даже через секунду после другого сбоя; следующие 409 — как обычно. */
    @Test
    void firstConflictIsAlwaysReported(CapturedOutput output) {
        stub.respond(
                "getUpdates",
                Reply.error(500, "Internal Server Error", null),
                Reply.error(409, "Conflict", null),
                Reply.error(409, "Conflict", null),
                Reply.hang(LONG));

        poller.start();
        stub.await("getUpdates", 4);
        await(() -> pauses.size() == 3);

        var warnings = output.getOut()
                .lines()
                .filter(line -> line.contains("telegram polling failed"))
                .toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1)).contains("conflict (HTTP 409)");
    }

    @Test
    void recoveryIsLogged(CapturedOutput output) {
        stub.respond(
                "getUpdates", Reply.error(500, "Internal Server Error", null), Reply.updates("[]"), Reply.hang(LONG));

        poller.start();
        stub.await("getUpdates", 3);

        await(() -> output.getOut().contains("telegram polling recovered after 1 failures"));
    }

    @Test
    void failingUpdateIsRetriedThenSkipped(CapturedOutput output) {
        var attempts = new CopyOnWriteArrayList<Long>();
        handler = update -> {
            attempts.add(update.updateId());
            if (update.updateId() == 20) {
                throw new InternalException("handler failed");
            }
            handled.add(update.updateId());
        };
        poller = poller(pauses::add);
        var batch = Reply.updates("[" + update(20, "/start abc") + ", " + update(21, "привет") + "]");
        stub.respond("getUpdates", batch, batch, batch, Reply.hang(LONG));

        poller.start();
        var requests = stub.await("getUpdates", 4);

        assertThat(attempts).containsExactly(20L, 20L, 20L, 21L);
        assertThat(handled).containsExactly(21L);
        assertThat(requests)
                .extracting(request -> request.body().path("offset").asLong(-1))
                .containsExactly(-1L, 20L, 20L, 22L);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(output.getOut()).contains("telegram update 20 skipped after 3 failed attempts");
    }

    /**
     * База недоступна — сообщение не теряется: временный сбой базы не засчитывается в попытки сообщения, оно
     * повторяется с паузами, пока не пройдёт. В логе — класс сбоя без текста драйвера и стека.
     */
    @Test
    void transientDatabaseFailureNeverSkipsTheUpdate(CapturedOutput output) {
        var attempts = new CopyOnWriteArrayList<Long>();
        handler = update -> {
            attempts.add(update.updateId());
            if (attempts.size() <= 5) {
                throw new DataAccessResourceFailureException(
                        "driver says: connection to secret-host refused", new SQLException("refused", "08001"));
            }
            handled.add(update.updateId());
        };
        poller = poller(pauses::add);
        var stop = Reply.updates("[" + update(50, "/stop") + "]");
        stub.respond("getUpdates", stop, stop, stop, stop, stop, stop, Reply.hang(LONG));

        poller.start();
        var requests = stub.await("getUpdates", 7);

        assertThat(attempts).containsExactly(50L, 50L, 50L, 50L, 50L, 50L);
        assertThat(handled).containsExactly(50L);
        assertThat(requests)
                .extracting(request -> request.body().path("offset").asLong(-1))
                .containsExactly(-1L, 50L, 50L, 50L, 50L, 50L, 51L);
        assertThat(output.getOut())
                .doesNotContain("skipped")
                .contains("telegram update 50 not handled: org.springframework.dao.DataAccessResourceFailureException"
                        + " (SQLState 08001)")
                .doesNotContain("secret-host")
                .doesNotContain("\tat ");
    }

    /** Обработанное с последнего опроса подтверждается при остановке — после рестарта оно не придёт снова. */
    @Test
    void stopConfirmsHandledUpdates() throws Exception {
        var handling = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler = update -> {
            handling.countDown();
            awaitLatch(release);
            handled.add(update.updateId());
        };
        poller = poller(pauses::add);
        stub.respond("getUpdates", Reply.updates("[" + update(40, "привет") + "]"));

        poller.start();
        assertThat(handling.await(20, TimeUnit.SECONDS)).isTrue();
        var stopping = Thread.ofVirtual().start(poller::stop);
        await(() -> !poller.isRunning());
        release.countDown();
        stopping.join(Duration.ofSeconds(10));

        assertThat(handled).containsExactly(40L);
        assertThat(stub.requests("getUpdates"))
                .extracting(
                        request -> request.body().path("offset").asLong(-1),
                        request -> request.body().path("timeout").asInt())
                .containsExactly(tuple(-1L, 50), tuple(41L, 0));
    }

    /** Ошибка ({@link Error}) не убивает поток молча: приём останавливается с ERROR. */
    @Test
    void errorStopsPollingLoudly(CapturedOutput output) {
        handler = update -> {
            throw new AssertionError("broken");
        };
        poller = poller(pauses::add);
        stub.respond("getUpdates", Reply.updates("[" + update(60, "привет") + "]"));

        poller.start();
        await(poller::terminated);

        assertThat(poller.isRunning()).isFalse();
        assertThat(output.getOut()).contains("ERROR").contains("telegram polling stopped by an unexpected error");
    }

    /** Пока прежний поток не закончил (остановка не дождалась его), новый не запускается. */
    @Test
    void startWaitsForThePreviousThread() throws Exception {
        var handling = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler = update -> {
            handling.countDown();
            awaitLatch(release);
        };
        poller = new TelegramPoller(
                api, update -> handler.accept(update), POLL_TIMEOUT, clock, pauses::add, Duration.ofMillis(100));
        stub.respond("getUpdates", Reply.updates("[" + update(70, "привет") + "]"));

        poller.start();
        assertThat(handling.await(20, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        poller.start();

        assertThat(poller.isRunning()).isFalse();
        assertThat(poller.terminated()).isFalse();
        release.countDown();
        await(poller::terminated);
        stub.respond("getUpdates", Reply.hang(LONG));
        poller.start();
        assertThat(poller.isRunning()).isTrue();
    }

    @Test
    void stopInterruptsTheLongPoll() {
        stub.respond("getUpdates", Reply.hang(LONG));

        poller.start();
        stub.await("getUpdates", 1);
        var started = System.nanoTime();
        poller.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(poller.isRunning()).isFalse();
        assertThat(poller.terminated()).isTrue();
        assertThat(stub.requests("getUpdates")).hasSize(1);
    }

    @Test
    void stopInterruptsThePauseAfterFailure() {
        stub.respond("getUpdates", Reply.error(500, "Internal Server Error", null));
        poller = poller(pause -> {
            pauses.add(pause);
            Thread.sleep(pause);
        });

        poller.start();
        stub.await("getUpdates", 1);
        await(() -> pauses.size() == 1);
        var started = System.nanoTime();
        poller.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(poller.terminated()).isTrue();
        assertThat(stub.requests("getUpdates")).hasSize(1);
    }

    @Test
    void botWithoutTokenIsNotPolled() {
        var disabled = new TelegramPoller(
                TelegramApi.disabled(), update -> handled.add(update.updateId()), POLL_TIMEOUT, clock, pauses::add);

        disabled.start();

        assertThat(disabled.isRunning()).isFalse();
        assertThat(disabled.terminated()).isTrue();
        disabled.stop();
    }

    private TelegramPoller poller(TelegramPoller.Pause pause) {
        return new TelegramPoller(api, update -> handler.accept(update), POLL_TIMEOUT, clock, pause);
    }

    private static String update(long id, String text) {
        return """
                {"update_id": %d, "message": {"message_id": 1, "from": {"id": 5, "is_bot": false},
                 "chat": {"id": 5, "type": "private"}, "text": "%s"}}""".formatted(id, text);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
