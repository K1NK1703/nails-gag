package gag.nails.api.internal.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gag.nails.analytics.AnalyticsReports;
import gag.nails.analytics.model.Metric;
import gag.nails.analytics.model.MetricTotal;
import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.api.internal.observability.RequestLogFilter;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestBodySpec;

/**
 * Приём событий не читает cookie сессии: {@code sendBeacon} шлёт её вместе с событием, но политика конфиденциальности
 * обещает события «без cookie и без связи с личностью». Строка журнала запроса без {@code accountId} в MDC, ответ без
 * {@code Set-Cookie} — и для живой сессии, и для негодной cookie, которую любой другой адрес снял бы.
 *
 * <p>Строки журнала запроса читаются своим приёмником Logback на логгере {@link RequestLogFilter}, а не из консоли:
 * формат консоли (текст или JSON) общий для всех контекстов прогона, и переключать его ради этого теста нельзя.
 */
@ApiTest
class AnalyticsSessionTest extends ApiTestSupport {

    private static final String EVENTS = "/api/analytics/events";
    private static final String PAGE_VIEW = "{\"name\":\"page_view\",\"path\":\"/\"}";
    private static final String ACCOUNT_ID = "accountId";

    @Autowired
    AnalyticsReports reports;

    /** Браузер этого теста: посетитель не совпадёт с посетителем другого теста. */
    private final String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/141.0 AnalyticsSessionTest/" + UUID.randomUUID();

    private final Logger requestLog = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> requestLines = new ListAppender<>();

    @BeforeEach
    void captureRequestLog() {
        requestLines.start();
        requestLog.addAppender(requestLines);
    }

    @AfterEach
    void releaseRequestLog() {
        requestLog.detachAppender(requestLines);
        requestLines.stop();
    }

    @Test
    void signedInEventIsNotLinkedToAccount() {
        var session = signInClient();
        var before = pageViews();

        var result = as(session, beacon())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();
        // Для сравнения: обычный запрос того же вошедшего размечен аккаунтом.
        as(session, client.get().uri("/api/test/whoami"))
                .header("X-Forwarded-For", nextIp())
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(pageViews()).isEqualTo(before + 1);
        assertThat(awaitRequestLine("GET /api/test/whoami -> 200").getMDCPropertyMap())
                .containsEntry(ACCOUNT_ID, session.accountId().toString());
        assertThat(awaitRequestLine("POST " + EVENTS + " -> 204").getMDCPropertyMap())
                .doesNotContainKey(ACCOUNT_ID);
    }

    /**
     * Негодную cookie любой другой адрес снимает в ответе ({@code Set-Cookie} с истёкшим сроком); приём её не читает и
     * поэтому не трогает.
     */
    @Test
    void invalidCookieIsNotTouched() {
        var before = pageViews();

        var events = beacon().cookie(SESSION_COOKIE, "not-a-session")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();
        var other = client.get()
                .uri("/api/test/maybe")
                .header("X-Forwarded-For", nextIp())
                .cookie(SESSION_COOKIE, "not-a-session")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();

        assertThat(events.getResponseHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(other.getResponseHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header).startsWith(SESSION_COOKIE + "="));
        assertThat(pageViews()).isEqualTo(before + 1);
    }

    private RequestBodySpec beacon() {
        var spec = client.post()
                .uri(EVENTS)
                .header(HttpHeaders.ORIGIN, SITE)
                .header("X-Forwarded-For", nextIp())
                .header(HttpHeaders.USER_AGENT, userAgent);
        spec.contentType(MediaType.APPLICATION_JSON).body(PAGE_VIEW);
        return spec;
    }

    private long pageViews() {
        var today = LocalDate.now(clock);
        return reports.summary(today, today, developerActor()).metrics().stream()
                .filter(metric -> metric.metric() == Metric.PAGE_VIEW)
                .mapToLong(MetricTotal::total)
                .sum();
    }

    /** Строка журнала запроса этого теста, сообщение которой начинается с {@code prefix}; ждёт, пока её запишут. */
    private ILoggingEvent awaitRequestLine(String prefix) {
        return await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> lines().stream()
                                .filter(event -> event.getFormattedMessage().startsWith(prefix))
                                .findFirst(),
                        found -> found.isPresent())
                .orElseThrow();
    }

    /** Копия записанного: приёмник пишет из потоков Tomcat под своей блокировкой. */
    private List<ILoggingEvent> lines() {
        synchronized (requestLines) {
            return List.copyOf(requestLines.list);
        }
    }
}
