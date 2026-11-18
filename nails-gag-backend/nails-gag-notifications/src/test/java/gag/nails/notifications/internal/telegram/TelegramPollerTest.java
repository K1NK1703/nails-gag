package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
            new TelegramApi.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2), LONG.plusSeconds(10)));
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

    @Test
    void failuresAreLoggedAtMostOncePerMinute(CapturedOutput output) {
        stub.respond(
                "getUpdates",
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.error(500, "Internal Server Error", null),
                Reply.hang(LONG));
        poller = poller(pause -> {
            pauses.add(pause);
            if (pauses.size() == 3) {
                clock.advance(Duration.ofMinutes(1));
            }
        });

        poller.start();
        stub.await("getUpdates", 4);

        var warnings = output.getOut()
                .lines()
                .filter(line -> line.contains("telegram polling failed"))
                .toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("telegram getUpdates failed: HTTP 500");

        stub.reset();
        stub.respond("getUpdates", Reply.error(500, "Internal Server Error", null), Reply.hang(LONG));
        await(() -> output.getOut().contains("2 more failures since the previous warning"));
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
