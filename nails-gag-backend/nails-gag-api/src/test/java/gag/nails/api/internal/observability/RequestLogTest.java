package gag.nails.api.internal.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Строка журнала на каждый запрос {@code /api/*} (спецификация API, §13): метод, шаблон адреса без значений,
 * статус и время; у вошедшего — поле {@code accountId}. Консоль — в JSON (ECS), как в приложении.
 */
@ApiTest
// У тестов api нет application.yaml приложения, и консоль у них текстовая; JSON включён, чтобы проверять поля строки
// (accountId) так, как их видит сборщик логов в работе.
@TestPropertySource(properties = "logging.structured.format.console=ecs")
@ExtendWith(OutputCaptureExtension.class)
class RequestLogTest extends ApiTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** Логгер строки запроса. */
    private static final String LOGGER = "gag.nails.api.internal.observability.RequestLogFilter";

    @Test
    void signedInRequestLogsRouteStatusAndAccount(CapturedOutput output) {
        var session = signInClient();

        as(session, client.get().uri("/api/me")).exchange().expectStatus().isOk();

        var line = awaitLine(output, session.accountId(), "GET /api/me -> ");
        assertThat(line.get("message")).asString().matches("GET /api/me -> 200 \\d+ms");
        assertThat(line.get("accountId")).isEqualTo(session.accountId().toString());
    }

    @Test
    void routeTemplateHidesIds(CapturedOutput output) {
        var master = signInMaster();

        as(master, client.get().uri("/api/documents/policy/versions/x")).exchange();

        var line = awaitLine(output, master.accountId(), "GET /api/documents/");
        assertThat(line.get("message"))
                .asString()
                .matches(Pattern.quote("GET /api/documents/{kind:policy|offer}/versions/{version} -> ")
                        + "\\d{3} \\d+ms");
    }

    @Test
    void queryStringIsNeverLogged(CapturedOutput output) {
        var session = signInClient();
        var phone = uniquePhone();

        as(session, client.get().uri("/api/me?phone={phone}", phone))
                .exchange()
                .expectStatus()
                .isOk();

        var line = awaitLine(output, session.accountId(), "GET /api/me -> ");
        assertThat(line.get("message")).asString().doesNotContain("?");
        assertThat(output.getAll()).doesNotContain(phone.substring(1));
    }

    @Test
    void unknownAddressLogsPathWithoutQuery(CapturedOutput output) {
        var phone = uniquePhone();

        client.get()
                .uri("/api/nope?phone={phone}", phone)
                .exchange()
                .expectStatus()
                .isNotFound();

        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(lines(output, "GET /api/nope -> ")).isNotEmpty());
        assertThat(lines(output, "GET /api/nope -> "))
                .allSatisfy(line -> assertThat(line.get("message")).asString().matches("GET /api/nope -> 404 \\d+ms"));
        assertThat(output.getAll()).doesNotContain(phone.substring(1));
    }

    @Test
    void anonymousRequestHasNoAccount(CapturedOutput output) {
        client.get().uri("/api/me").exchange().expectStatus().isUnauthorized();

        await().atMost(WAIT)
                .untilAsserted(
                        () -> assertThat(lines(output, "GET /api/me -> 401 ")).isNotEmpty());
        assertThat(lines(output, "GET /api/me -> 401 "))
                .allSatisfy(line -> assertThat(line).doesNotContainKey("accountId"));
    }

    /** Строка журнала запроса вошедшего с началом сообщения {@code prefix}; ждёт, пока фильтр её запишет. */
    private static Map<String, Object> awaitLine(CapturedOutput output, UUID accountId, String prefix) {
        var account = accountId.toString();
        return await().atMost(WAIT)
                .until(
                        () -> lines(output, prefix).stream()
                                .filter(line -> account.equals(line.get("accountId")))
                                .findFirst(),
                        found -> found.isPresent())
                .orElseThrow();
    }

    /**
     * Строки журнала запросов в JSON, сообщение которых начинается с {@code prefix}. Строки других логгеров
     * (например, {@code ProblemHandler} с похожим началом) не считаются.
     */
    private static List<Map<String, Object>> lines(CapturedOutput output, String prefix) {
        return output.getAll()
                .lines()
                .filter(line -> line.startsWith("{"))
                .<Map<String, Object>>map(line -> JsonPath.read(line, "$"))
                .filter(line -> line.get("log") instanceof Map<?, ?> log && LOGGER.equals(log.get("logger")))
                .filter(line -> line.get("message") instanceof String message && message.startsWith(prefix))
                .toList();
    }
}
