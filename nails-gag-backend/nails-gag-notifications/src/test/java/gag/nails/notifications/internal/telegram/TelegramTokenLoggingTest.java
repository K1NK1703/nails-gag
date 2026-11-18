package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import gag.nails.testsupport.MutableClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Токен бота — часть адреса каждого запроса к Bot API, а исключения клиента HTTP Spring пишут адрес в текст («I/O error
 * on POST request for "https://api.telegram.org/bot<токен>/…"»). Ни отправка, ни приём обновлений не выводят его ни
 * при каких сбоях — даже с подробным логом модуля и клиентов HTTP Spring.
 */
@ExtendWith(OutputCaptureExtension.class)
class TelegramTokenLoggingTest {

    private static final String SECRET = "never-log-this-token";
    private static final String TOKEN = "777777:" + SECRET;
    private static final List<String> VERBOSE = List.of(
            "gag.nails.notifications",
            "org.springframework.web",
            "org.springframework.http",
            "org.springframework.web.client");
    private static final RenderedText TEXT = RenderedText.of("Вы записаны на пн, 5 октября, 14:00");
    private static final ResolvedRecipient ANNA = new ResolvedRecipient(null, 4242L, "Анна");

    private final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
    private final TelegramStub stub = TelegramStub.started();

    @BeforeEach
    void verbose() {
        VERBOSE.forEach(name -> logging.setLogLevel(name, LogLevel.TRACE));
    }

    @AfterEach
    void quiet() {
        VERBOSE.forEach(name -> logging.setLogLevel(name, null));
        stub.close();
    }

    @Test
    void botTokenNeverLogged(CapturedOutput output) throws Exception {
        var timeouts = new TelegramApi.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofMillis(300));
        try (var unreachable = TelegramApi.connect("http://127.0.0.1:" + closedPort(), TOKEN, timeouts);
                var answering = TelegramApi.connect(stub.url(), TOKEN, timeouts)) {
            stub.respond(
                    "sendMessage",
                    Reply.error(500, "Internal Server Error", null),
                    Reply.error(403, "Forbidden: bot was blocked by the user", null),
                    Reply.html(502),
                    Reply.hang(Duration.ofSeconds(5)));
            var unreachableChannel = new TelegramChannel(unreachable);
            var channel = new TelegramChannel(answering);

            assertThat(unreachableChannel.send(ANNA, TEXT))
                    .isEqualTo(new SendResult.Retry(null, "telegram_unreachable"));
            assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_server_error"));
            assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Fail("telegram_blocked", true));
            assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_server_error"));
            assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_unreachable"));

            poll(unreachable, 2);
            stub.reset();
            stub.respond(
                    "getUpdates",
                    Reply.error(409, "Conflict: terminated by other getUpdates request", null),
                    Reply.error(401, "Unauthorized", null),
                    Reply.hang(Duration.ofSeconds(5)),
                    Reply.hang(Duration.ofSeconds(5)));
            poll(answering, 3);
        }

        assertThat(output.getAll()).contains("telegram getUpdates").doesNotContain(SECRET);
    }

    /** Приём обновлений до {@code failures} сбоев подряд; паузы не ждутся. */
    private static void poll(TelegramApi api, int failures) throws InterruptedException {
        var pauses = new CopyOnWriteArrayList<Duration>();
        var clock = new MutableClock(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC);
        var poller = new TelegramPoller(api, update -> {}, Duration.ofSeconds(50), clock, pause -> {
            pauses.add(pause);
            clock.advance(Duration.ofMinutes(1));
        });
        poller.start();
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (pauses.size() < failures && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        poller.stop();
        assertThat(pauses).hasSizeGreaterThanOrEqualTo(failures);
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
