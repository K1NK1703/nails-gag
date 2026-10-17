package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.model.DocumentKind;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.Provider;
import gag.nails.identity.model.RegistrationRequired;
import gag.nails.identity.model.SignedIn;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Трассы и записи журнала запроса (спецификация API, §13): span-ы SQL вложены в span HTTP-запроса и не несут значений
 * параметров — там бывают телефоны; у вошедшего span запроса несёт {@code account.id}, а записи журнала, ушедшие
 * через мост Logback → OpenTelemetry, — атрибут {@code accountId}. Экспорт трасс в тестах Boot выключен;
 * {@link AutoConfigureTracing} включает его, а span-ы и записи собирают {@link InMemorySpanExporter} и
 * {@link InMemoryLogRecordExporter} вместо OTLP. Вход — через подставной провайдер, как в
 * {@code IdentityConcurrencyTest}.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "nails-gag.booking.jobs.enabled=false",
            "nails-gag.identity.jobs.enabled=false",
            "nails-gag.identity.fake-provider.enabled=true"
        })
@AutoConfigureTracing
@Import({PostgresTestcontainers.class, ObservabilityTest.InMemoryExporters.class})
class ObservabilityTest {

    /** Публичный адрес, который читает базу: действующие редакции документов. */
    private static final String DOCUMENTS = "/api/documents";

    /** Профиль вошедшего: нужна сессия. */
    private static final String ME = "/api/me";

    /** Шаблон адреса в span-е запроса — ключ {@code uri} соглашения Spring по умолчанию. */
    private static final AttributeKey<String> ROUTE = AttributeKey.stringKey("uri");

    private static final AttributeKey<String> SPAN_ACCOUNT_ID = AttributeKey.stringKey("account.id");
    private static final AttributeKey<String> LOG_ACCOUNT_ID = AttributeKey.stringKey("accountId");

    /** Логгер строки запроса в {@code api}: имя области инструментирования у записей моста. */
    private static final String REQUEST_LOG = "gag.nails.api.internal.observability.RequestLogFilter";

    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");
    private static final Duration WAIT = Duration.ofSeconds(15);

    @LocalServerPort
    int port;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    InMemoryLogRecordExporter logs;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    SdkLoggerProvider loggerProvider;

    @Autowired
    LoginFlow loginFlow;

    @Autowired
    FakeProvider fake;

    @Autowired
    DocumentVersionService documents;

    @BeforeEach
    void forgetEarlierTelemetry() {
        flush();
        spans.reset();
        logs.reset();
    }

    @Test
    void sqlSpansAreChildrenOfRequestSpan() {
        var request = requestSpan(DOCUMENTS, null);
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
        var sql = sqlSpans(trace(requestSpan(DOCUMENTS, null)));

        assertThat(sql).isNotEmpty();
        assertThat(sql)
                .allSatisfy(span -> assertThat(span.getAttributes().asMap().keySet())
                        .noneSatisfy(key -> assertThat(key.getKey()).startsWith("jdbc.params")));
    }

    @Test
    void signedInRequestSpanAndLogRecordCarryAccount() {
        var signedIn = signIn();
        var accountId = signedIn.accountId().toString();

        var request = requestSpan(ME, signedIn.sessionToken());

        assertThat(request.getAttributes().get(SPAN_ACCOUNT_ID)).isEqualTo(accountId);
        var record = await().atMost(WAIT)
                .until(
                        () -> {
                            flush();
                            return logs.getFinishedLogRecordItems().stream()
                                    .filter(log -> log.getInstrumentationScopeInfo()
                                            .getName()
                                            .equals(REQUEST_LOG))
                                    .filter(log ->
                                            log.getSpanContext().getTraceId().equals(request.getTraceId()))
                                    .findFirst();
                        },
                        found -> found.isPresent())
                .orElseThrow();
        assertThat(body(record)).startsWith("GET " + ME + " -> 200 ");
        assertThat(record.getAttributes().get(LOG_ACCOUNT_ID)).isEqualTo(accountId);
    }

    @Test
    void anonymousRequestSpanHasNoAccount() {
        var request = requestSpan(DOCUMENTS, null);

        assertThat(request.getAttributes().get(SPAN_ACCOUNT_ID)).isNull();
    }

    /**
     * Запрос к {@code route} (с cookie сессии, если задан токен) и его span — серверный, с этим шаблоном адреса; ждёт,
     * пока span-ы дойдут до экспорта.
     */
    private SpanData requestSpan(String route, @Nullable String sessionToken) {
        RestClient.create("http://localhost:" + port)
                .get()
                .uri(route)
                .headers(headers -> {
                    if (sessionToken != null) {
                        // Имя повторяет SessionCookie из api: его internal из app не виден.
                        headers.add(HttpHeaders.COOKIE, "ng_session=" + sessionToken);
                    }
                })
                .retrieve()
                .toBodilessEntity();
        return await().atMost(WAIT)
                .until(
                        () -> {
                            flush();
                            return spans.getFinishedSpanItems().stream()
                                    .filter(span -> span.getKind() == SpanKind.SERVER)
                                    .filter(span ->
                                            route.equals(span.getAttributes().get(ROUTE)))
                                    .findFirst();
                        },
                        found -> found.isPresent())
                .orElseThrow();
    }

    /** Новый человек входит через подставной провайдер и регистрируется: сессия для запросов. */
    private SignedIn signIn() {
        var subject = "observability-" + UUID.randomUUID();
        var phone = "+79%09d".formatted(ThreadLocalRandom.current().nextInt(1_000_000_000));
        var profile = new FakeProfile(subject, phone, null, "Наблюдаемость", null);
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fake.issueCode(profile);
        var result = loginFlow.complete(
                Provider.FAKE, new AuthorizationResponse(code, start.state().state(), null), start.state(), CLIENT);
        assertThat(result).isInstanceOf(RegistrationRequired.class);
        var policy = documents.current(DocumentKind.POLICY).version();
        return loginFlow.completeRegistration(
                ((RegistrationRequired) result).ticket(),
                new CompleteRegistration(profile.displayName(), phone, policy),
                CLIENT);
    }

    /** Текст записи журнала. */
    private static String body(LogRecordData record) {
        var body = record.getBodyValue();
        return body == null ? "" : body.asString();
    }

    /** Всё, что ещё лежит в пакетных обработчиках, — в экспортёры. */
    private void flush() {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        loggerProvider.forceFlush().join(5, TimeUnit.SECONDS);
    }

    /** Все законченные span-ы трассы запроса. */
    private List<SpanData> trace(SpanData request) {
        return spans.getFinishedSpanItems().stream()
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
        for (var parent = byId.get(span.getParentSpanId());
                parent != null;
                parent = byId.get(parent.getParentSpanId())) {
            if (parent.getSpanId().equals(ancestor.getSpanId())) {
                return true;
            }
        }
        return false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryExporters {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        InMemoryLogRecordExporter inMemoryLogRecordExporter() {
            return InMemoryLogRecordExporter.create();
        }
    }
}
