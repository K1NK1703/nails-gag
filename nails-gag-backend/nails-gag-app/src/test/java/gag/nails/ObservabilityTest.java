package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Трассы запроса (спецификация API, §13): span-ы SQL вложены в span HTTP-запроса и не несут значений параметров —
 * там бывают телефоны. Экспорт трасс в тестах Boot выключен; {@link AutoConfigureTracing} включает его, а span-ы
 * собирает {@link InMemorySpanExporter} вместо OTLP.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
@Import({PostgresTestcontainers.class, ObservabilityTest.InMemorySpans.class})
class ObservabilityTest {

    /** Публичный адрес, который читает базу: действующие редакции документов. */
    private static final String ROUTE = "/api/documents";

    /** Шаблон адреса в span-е запроса — ключ {@code uri} соглашения Spring по умолчанию. */
    private static final String ROUTE_KEY = "uri";

    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    InMemorySpanExporter exporter;

    @Autowired
    SdkTracerProvider tracerProvider;

    @BeforeEach
    void forgetEarlierSpans() {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        exporter.reset();
    }

    @Test
    void sqlSpansAreChildrenOfRequestSpan() {
        var request = requestSpan();
        var trace = trace(request);
        var sql = sqlSpans(trace);

        assertThat(sql).isNotEmpty();
        var byId = trace.stream().collect(Collectors.toMap(SpanData::getSpanId, Function.identity()));
        assertThat(sql)
                .allSatisfy(span -> assertThat(descendsFrom(span, request, byId))
                        .as("span %s in request %s", span.getName(), request.getName())
                        .isTrue());
    }

    @Test
    void sqlSpansHaveNoParameterValues() {
        var sql = sqlSpans(trace(requestSpan()));

        assertThat(sql).isNotEmpty();
        assertThat(sql)
                .allSatisfy(span -> assertThat(span.getAttributes().asMap().keySet())
                        .noneSatisfy(key -> assertThat(key.getKey()).startsWith("jdbc.params")));
    }

    /** Запрос к {@link #ROUTE} и его span — серверный, с шаблоном адреса; ждёт, пока span-ы дойдут до экспорта. */
    private SpanData requestSpan() {
        RestClient.create("http://localhost:" + port)
                .get()
                .uri(ROUTE)
                .retrieve()
                .toBodilessEntity();
        return await().atMost(WAIT)
                .until(
                        () -> {
                            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
                            return exporter.getFinishedSpanItems().stream()
                                    .filter(span -> span.getKind() == SpanKind.SERVER)
                                    .filter(span -> ROUTE.equals(span.getAttributes().asMap().entrySet().stream()
                                            .filter(entry ->
                                                    entry.getKey().getKey().equals(ROUTE_KEY))
                                            .map(Map.Entry::getValue)
                                            .findFirst()
                                            .orElse(null)))
                                    .findFirst();
                        },
                        found -> found.isPresent())
                .orElseThrow();
    }

    /** Все законченные span-ы трассы запроса. */
    private List<SpanData> trace(SpanData request) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(request.getTraceId()))
                .toList();
    }

    /** span-ы SQL datasource-micrometer: у них есть текст запроса в {@code jdbc.query[…]}. */
    private static List<SpanData> sqlSpans(List<SpanData> trace) {
        return trace.stream()
                .filter(span -> span.getAttributes().asMap().keySet().stream()
                        .anyMatch(key -> key.getKey().startsWith("jdbc.query")))
                .toList();
    }

    /** {@code span} — потомок {@code ancestor} по цепочке родителей внутри трассы. */
    private static boolean descendsFrom(SpanData span, SpanData ancestor, Map<String, SpanData> byId) {
        var parentId = span.getParentSpanId();
        while (byId.containsKey(parentId) || parentId.equals(ancestor.getSpanId())) {
            if (parentId.equals(ancestor.getSpanId())) {
                return true;
            }
            parentId = byId.get(parentId).getParentSpanId();
        }
        return false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemorySpans {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
