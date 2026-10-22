package gag.nails.api.internal.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.common.error.InternalException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ответ {@code 500} и прочие {@code 5xx} без кода модуля: тело без подробностей, а в лог — ERROR с классом
 * исключения и стеком (спецификация API, §4.5, §13): по {@code traceId} ошибку находят в логе, и причина там должна
 * быть.
 */
@ApiTest
@ExtendWith(OutputCaptureExtension.class)
class ProblemLogTest extends ApiTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void unexpectedExceptionIsLoggedWithStack(CapturedOutput output) {
        expectProblem(
                client.get().uri("/api/test/throw/internal"), HttpStatus.INTERNAL_SERVER_ERROR, "common.internal");

        awaitStack(output, "GET /api/test/throw/internal failed", InternalException.class);
    }

    @Test
    void serverErrorStatusIsLoggedWithStack(CapturedOutput output) {
        expectProblem(
                client.get().uri("/api/test/throw/unavailable"), HttpStatus.SERVICE_UNAVAILABLE, "common.internal");

        awaitStack(output, "GET /api/test/throw/unavailable failed", ResponseStatusException.class);
    }

    /** Строка ERROR с сообщением, а сразу за ней — класс исключения и кадры стека. */
    private static void awaitStack(CapturedOutput output, String message, Class<?> exception) {
        await().atMost(WAIT).untilAsserted(() -> {
            var lines = output.getAll().lines().toList();
            var at = -1;
            for (var i = 0; i < lines.size() && at < 0; i++) {
                if (lines.get(i).contains(message)) {
                    at = i;
                }
            }
            assertThat(at).as(message).isNotNegative();
            assertThat(lines.get(at)).contains("ERROR");
            assertThat(String.join("\n", lines.subList(at + 1, Math.min(lines.size(), at + 40))))
                    .contains(exception.getName())
                    .containsPattern("\\s+at gag\\.nails\\.");
        });
    }
}
