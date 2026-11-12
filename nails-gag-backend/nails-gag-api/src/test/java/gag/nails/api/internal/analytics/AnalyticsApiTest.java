package gag.nails.api.internal.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.analytics.AnalyticsReports;
import gag.nails.analytics.model.AnalyticsSummary;
import gag.nails.analytics.model.KeyCount;
import gag.nails.analytics.model.Metric;
import gag.nails.analytics.model.MetricTotal;
import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestBodySpec;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Счётчик посещений по HTTP (спецификация счётчика, §4): приём событий без сессии и без {@code X-Requested-With}, но с
 * проверкой источника; любое неверное тело — молча {@code 204}; отчёты с правом «Статистика посещений». Что и как
 * считается, проверяет модуль; здесь — что событие доходит до него целиком или не доходит вовсе.
 *
 * <p>Каждый тест переводит часы на свой день {@link #freshDay()} и сравнивает счётчики этого дня до и после запроса;
 * у каждого теста свой {@code User-Agent} (посетитель дня — это IP и браузер) и свои IP: корзина лимита приёма — по
 * IP, а часы перед каждым тестом возвращаются назад.
 */
@ApiTest
class AnalyticsApiTest extends ApiTestSupport {

    private static final String EVENTS = "/api/analytics/events";
    private static final String PAGE_VIEW = "{\"name\":\"page_view\",\"path\":\"/\"}";
    private static final int MAX_BODY_BYTES = 1024;

    @Autowired
    AnalyticsReports reports;

    @LocalServerPort
    int port;

    /** Браузер этого теста: настоящий на вид, но свой — посетитель не совпадёт с посетителем другого теста. */
    private final String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/141.0 AnalyticsApiTest/" + UUID.randomUUID();

    private LocalDate day = LocalDate.EPOCH;

    @BeforeEach
    void onOwnDay() {
        day = freshDay();
        clock.set(day.atTime(12, 0).atZone(clock.getZone()).toInstant());
    }

    @Test
    void beaconWithoutSessionAndRequestedWithIsCounted() {
        var before = pageViews();

        beacon(nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();

        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /** Без {@code Origin} источник — {@code Referer} своего сайта, как у остальных изменяющих запросов. */
    @Test
    void refererStandsInForMissingOrigin() {
        var before = pageViews();

        client.post()
                .uri(EVENTS)
                .header(HttpHeaders.REFERER, SITE + "/booking")
                .header("X-Forwarded-For", nextIp())
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /** Второй барьер CSRF остаётся: чужие сайты не накрутят счётчики из браузеров посетителей. */
    @Test
    void foreignSourceIsRejected() {
        var before = summary();

        for (var origin : List.of("https://evil.example", SITE + ".evil.example", "null")) {
            expectCsrf(client.post()
                    .uri(EVENTS)
                    .header(HttpHeaders.ORIGIN, origin)
                    .header("X-Forwarded-For", nextIp())
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PAGE_VIEW));
        }
        expectCsrf(client.post()
                .uri(EVENTS)
                .header(HttpHeaders.REFERER, "https://evil.example/")
                .header("X-Forwarded-For", nextIp())
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW));
        expectCsrf(client.post()
                .uri(EVENTS)
                .header("X-Forwarded-For", nextIp())
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW));

        assertThat(summary()).isEqualTo(before);
    }

    @Test
    void crossSiteFetchMetadataIsRejected() {
        var before = pageViews();

        for (var site : List.of("cross-site", "same-site", "none")) {
            expectCsrf(beacon(nextIp())
                    .header("Sec-Fetch-Site", site)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PAGE_VIEW));
        }
        assertThat(pageViews()).isEqualTo(before);

        beacon(nextIp())
                .header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /** Без {@code X-Requested-With} проходит только сам адрес приёма и только {@code POST}. */
    @Test
    void requestedWithIsWaivedOnlyForEvents() {
        for (var uri : List.of(EVENTS + "x", EVENTS + "/x", "/api/analytics/summary", "/api/test/echo")) {
            expectCsrf(client.post()
                    .uri(uri)
                    .header(HttpHeaders.ORIGIN, SITE)
                    .header("X-Forwarded-For", nextIp())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PAGE_VIEW));
        }
        expectCsrf(client.put()
                .uri(EVENTS)
                .header(HttpHeaders.ORIGIN, SITE)
                .header("X-Forwarded-For", nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW));
    }

    /**
     * Тип содержимого не проверяется: тело читается как JSON и при {@code application/json} (Blob фронта), и при
     * {@code text/plain} ({@code sendBeacon} со строкой), и без заголовка — Spring не отвечает {@code 415}.
     */
    @Test
    void bodyIsJsonWhateverTheContentType() throws IOException {
        var before = pageViews();

        beacon(nextIp())
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();
        beacon(nextIp())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();
        var body = PAGE_VIEW.getBytes(StandardCharsets.UTF_8);
        var response = raw(head(nextIp(), "Content-Length: " + body.length), body);

        assertThat(response.status()).startsWith("HTTP/1.1 204");
        assertThat(pageViews()).isEqualTo(before + 3);
    }

    /** Тело ровно 1 КБ разбирается; на байт больше — молча не считается. */
    @Test
    void bodyOverOneKilobyteIsIgnored() {
        var before = pageViews();

        beacon(nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(padded(PAGE_VIEW, MAX_BODY_BYTES))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(pageViews()).isEqualTo(before + 1);

        beacon(nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(padded(PAGE_VIEW, MAX_BODY_BYTES + 1))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /** Без {@code Content-Length} (по частям) предел тот же: читается не больше 1 КБ и байта. */
    @Test
    void chunkedBodyOverOneKilobyteIsIgnored() throws IOException {
        var before = pageViews();
        var body = padded(PAGE_VIEW, 2 * MAX_BODY_BYTES).getBytes(StandardCharsets.UTF_8);
        var chunked = new StringBuilder()
                .append(Integer.toHexString(body.length))
                .append("\r\n")
                .append(new String(body, StandardCharsets.UTF_8))
                .append("\r\n0\r\n\r\n");

        var response = raw(
                head(nextIp(), "Transfer-Encoding: chunked"), chunked.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).startsWith("HTTP/1.1 204");
        assertThat(response.headers()).anyMatch(header -> header.equalsIgnoreCase("Connection: close"));
        assertThat(pageViews()).isEqualTo(before);

        var small = PAGE_VIEW.getBytes(StandardCharsets.UTF_8);
        var counted = raw(
                head(nextIp(), "Transfer-Encoding: chunked"),
                (Integer.toHexString(small.length) + "\r\n" + PAGE_VIEW + "\r\n0\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(counted.status()).startsWith("HTTP/1.1 204");
        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /**
     * Заявленный {@code Content-Length} больше 1 КБ — ответ сразу, не дожидаясь тела: здесь клиент отправил лишь часть
     * и ждёт, сокет с таймаутом в 5 секунд не дождался бы ответа, если бы сервер читал тело целиком.
     */
    @Test
    void declaredLargeBodyIsNotAwaited() throws IOException {
        var before = pageViews();

        var response = raw(
                head(nextIp(), "Content-Length: 5000000"),
                padded(PAGE_VIEW, 2 * MAX_BODY_BYTES).getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).startsWith("HTTP/1.1 204");
        assertThat(response.headers()).anyMatch(header -> header.equalsIgnoreCase("Connection: close"));
        assertThat(pageViews()).isEqualTo(before);
    }

    /** Не JSON, не тот JSON, неизвестное событие, неверное значение поля — {@code 204}, счётчики не меняются. */
    @Test
    void malformedBodiesAreIgnoredSilently() {
        var before = summary();

        for (var body : List.of(
                "",
                "not json",
                "{\"name\":\"page_view\",\"path\":\"/\"",
                "[" + PAGE_VIEW + "]",
                "null",
                "\"page_view\"",
                PAGE_VIEW + PAGE_VIEW,
                "{\"name\":\"purchase\",\"path\":\"/\"}",
                "{\"name\":\"gallery_open\",\"index\":\"first\"}",
                "{\"name\":\"gallery_open\",\"index\":100}",
                "{\"name\":\"page_view\",\"path\":{\"nested\":true}}",
                "{\"name\":\"page_view\",\"place\":\"hero\",\"path\":\"/\"}")) {
            beacon(nextIp())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange()
                    .expectStatus()
                    .isNoContent()
                    .expectBody()
                    .isEmpty();
        }

        assertThat(summary()).isEqualTo(before);
    }

    /**
     * Незнакомые поля ({@code ts} фронта) пропускаются, знакомые доходят до модуля: вход на сайт с {@code entry} и
     * {@code referrer} считает источник; события всех видов — свои счётчики.
     */
    @Test
    void knownFieldsReachTheModuleAndUnknownAreSkipped() {
        var referrer = "ref-" + UUID.randomUUID().toString().substring(0, 8) + ".example";
        var before = summary();

        for (var event : List.<Map<String, Object>>of(
                Map.of("name", "page_view", "path", "/booking", "entry", true, "referrer", referrer, "ts", 1),
                Map.of("name", "cta_click", "place", "hero", "ts", 2, "extra", Map.of("a", 1)),
                Map.of("name", "contact_click", "channel", "telegram"),
                Map.of("name", "gallery_open", "index", 7),
                Map.of("name", "faq_open", "id", "price-list"),
                Map.of("name", "map_open"))) {
            beacon(nextIp())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        }

        var after = summary();
        assertThat(count(after, Metric.PAGE_VIEW, "/booking"))
                .isEqualTo(count(before, Metric.PAGE_VIEW, "/booking") + 1);
        assertThat(count(after, Metric.SOURCE, referrer)).isEqualTo(1);
        assertThat(count(after, Metric.DEVICE, "desktop")).isEqualTo(count(before, Metric.DEVICE, "desktop") + 1);
        assertThat(count(after, Metric.CTA_CLICK, "hero")).isEqualTo(count(before, Metric.CTA_CLICK, "hero") + 1);
        assertThat(count(after, Metric.CONTACT_CLICK, "telegram"))
                .isEqualTo(count(before, Metric.CONTACT_CLICK, "telegram") + 1);
        assertThat(count(after, Metric.GALLERY_OPEN, "7")).isEqualTo(count(before, Metric.GALLERY_OPEN, "7") + 1);
        assertThat(count(after, Metric.FAQ_OPEN, "price-list"))
                .isEqualTo(count(before, Metric.FAQ_OPEN, "price-list") + 1);
        assertThat(count(after, Metric.MAP_OPEN, "map")).isEqualTo(count(before, Metric.MAP_OPEN, "map") + 1);
    }

    /**
     * IP посетителя — адрес клиента, который Tomcat взял из {@code X-Forwarded-For} доверенного прокси (спецификация
     * API, §5.4): тот же адрес и браузер — один посетитель дня, другой адрес — второй, IPv6 — тоже адрес.
     */
    @Test
    void visitorIpComesFromTrustedProxy() {
        var before = summary().visitors();

        for (var ip : List.of("198.51.100.200", "198.51.100.200", "198.51.100.201", "2001:db8:5:6::7")) {
            beacon(ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PAGE_VIEW)
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        }

        assertThat(summary().visitors()).isEqualTo(before + 3);
    }

    /** Сессия приёму не нужна и не мешает: вошедший посетитель считается так же. */
    @Test
    void signedInVisitorIsCountedToo() {
        var session = signInClient();
        var before = pageViews();

        as(session, beacon(nextIp()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(pageViews()).isEqualTo(before + 1);
    }

    /** Робот по {@code User-Agent} — {@code 204}, ничего не считается: отбраковку делает модуль. */
    @Test
    void robotsAreIgnored() {
        var before = summary();

        client.post()
                .uri(EVENTS)
                .header(HttpHeaders.ORIGIN, SITE)
                .header("X-Forwarded-For", nextIp())
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; YandexBot/3.0)")
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(summary()).isEqualTo(before);
    }

    @Test
    void developerReadsSummary() {
        beacon(nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();

        var summary =
                noStore(as(signInDeveloper(), client.get().uri("/api/analytics/summary?from={d}&to={d}", day, day)));

        assertThat(field(summary, "from").asString()).isEqualTo(day.toString());
        assertThat(field(summary, "to").asString()).isEqualTo(day.toString());
        assertThat(field(summary, "visitors").asLong()).isPositive();
        var metrics = field(summary, "metrics").values();
        assertThat(metrics)
                .extracting(metric -> field(metric, "metric").asString())
                .containsExactlyElementsOf(Arrays.stream(Metric.values())
                        .filter(metric -> metric != Metric.VISITORS)
                        .map(Metric::name)
                        .toList());
        var pageViews = field(summary, "metrics").get(0);
        assertThat(field(pageViews, "total").asLong()).isEqualTo(pageViews());
        assertThat(field(pageViews, "keys").values()).anySatisfy(key -> {
            assertThat(field(key, "key").asString()).isEqualTo("/");
            assertThat(field(key, "count").asLong()).isPositive();
        });
    }

    @Test
    void developerReadsDaily() {
        beacon(nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(PAGE_VIEW)
                .exchange()
                .expectStatus()
                .isNoContent();

        var daily = noStore(as(
                signInDeveloper(),
                client.get().uri("/api/analytics/daily?from={from}&to={to}", day.minusDays(2), day)));

        assertThat(daily.values())
                .extracting(totals -> field(totals, "day").asString())
                .containsExactly(day.minusDays(2).toString(), day.minusDays(1).toString(), day.toString());
        var today = daily.get(2);
        assertThat(field(today, "pageViews").asLong()).isEqualTo(pageViews());
        assertThat(field(today, "visitors").asLong()).isPositive();
        assertThat(field(today, "ctaClicks").isNumber()).isTrue();
        assertThat(field(today, "contactClicks").isNumber()).isTrue();
    }

    @Test
    void periodIsChecked() {
        var developer = signInDeveloper();

        for (var path : List.of("summary", "daily")) {
            expectInvalid(
                    as(developer, client.get().uri("/api/analytics/{p}?from={f}&to={t}", path, day, day.minusDays(1))),
                    "to");
            expectInvalid(
                    as(
                            developer,
                            client.get().uri("/api/analytics/{p}?from={f}&to={t}", path, day.minusDays(366), day)),
                    "to");
            expectInvalid(
                    as(developer, client.get().uri("/api/analytics/{p}?from=1999-12-31&to=2000-01-01", path)), "from");
            expectInvalid(as(developer, client.get().uri("/api/analytics/{p}?from={f}", path, day)), "to");
            expectInvalid(as(developer, client.get().uri("/api/analytics/{p}?from=x&to={t}", path, day)), "from");
        }
    }

    @Test
    void masterIsForbiddenClientNotFoundAnonymousUnauthenticated() {
        var master = signInMaster();
        var customer = signInClient();

        for (var path : List.of("summary", "daily")) {
            var uri = "/api/analytics/" + path + "?from=" + day + "&to=" + day;
            expectProblem(as(master, client.get().uri(uri)), HttpStatus.FORBIDDEN, "common.access_denied");
            expectProblem(as(customer, client.get().uri(uri)), HttpStatus.NOT_FOUND, "common.not_found");
            expectProblem(client.get().uri(uri), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        }
    }

    /** Сквозной сценарий спецификации счётчика, §3.5: раздел выдаётся мастеру личным переопределением. */
    @Test
    void overrideGrantsAnalyticsToMaster() {
        var developer = signInDeveloper();
        var master = signInMaster();
        var summary = "/api/analytics/summary?from=" + day + "&to=" + day;

        expectProblem(as(master, client.get().uri(summary)), HttpStatus.FORBIDDEN, "common.access_denied");

        as(developer, csrf(client.put().uri("/api/access/accounts/{id}/overrides/analytics", master.accountId())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("level", "READ"))
                .exchange()
                .expectStatus()
                .isOk();
        noStore(as(master, client.get().uri(summary)));

        as(developer, csrf(client.delete().uri("/api/access/accounts/{id}/overrides/analytics", master.accountId())))
                .exchange()
                .expectStatus()
                .isOk();
        expectProblem(as(master, client.get().uri(summary)), HttpStatus.FORBIDDEN, "common.access_denied");
    }

    /** Запрос приёма, как его шлёт {@code sendBeacon}: {@code Origin} сайта, без {@code X-Requested-With}. */
    private RequestBodySpec beacon(String ip) {
        return client.post()
                .uri(EVENTS)
                .header(HttpHeaders.ORIGIN, SITE)
                .header("X-Forwarded-For", ip)
                .header(HttpHeaders.USER_AGENT, userAgent);
    }

    private static void expectCsrf(RequestHeadersSpec<?> spec) {
        expectProblem(spec, HttpStatus.FORBIDDEN, "common.csrf");
    }

    private static void expectInvalid(RequestHeadersSpec<?> spec, String field) {
        var problem = json(spec, HttpStatus.BAD_REQUEST);
        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo(field);
    }

    private static JsonNode noStore(RequestHeadersSpec<?> spec) {
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    /** JSON, дополненный пробелами до {@code bytes} байт (ASCII). */
    private static String padded(String json, int bytes) {
        return json + " ".repeat(bytes - json.length());
    }

    private AnalyticsSummary summary() {
        return reports.summary(day, day, developerActor());
    }

    private long pageViews() {
        return summary().metrics().stream()
                .filter(metric -> metric.metric() == Metric.PAGE_VIEW)
                .mapToLong(MetricTotal::total)
                .sum();
    }

    private static long count(AnalyticsSummary summary, Metric metric, String key) {
        return summary.metrics().stream()
                .filter(total -> total.metric() == metric)
                .flatMap(total -> total.keys().stream())
                .filter(keyCount -> keyCount.key().equals(key))
                .mapToLong(KeyCount::count)
                .sum();
    }

    /** Начало запроса приёма для отправки по сокету как есть: заголовки сайта и {@code extra}, пустая строка. */
    private String head(String ip, String extra) {
        return "POST " + EVENTS + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Origin: " + SITE + "\r\n"
                + "X-Forwarded-For: " + ip + "\r\n"
                + "User-Agent: " + userAgent + "\r\n"
                + extra + "\r\n"
                + "\r\n";
    }

    /**
     * Запрос по сокету как есть — так, как его не отправит клиент Spring: без {@code Content-Type}, по частям или с
     * телом короче заявленного. Ответ ждётся не дольше 5 секунд.
     */
    private RawResponse raw(String head, byte[] body) throws IOException {
        try (var socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            var out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var status = in.readLine();
            var headers = new ArrayList<String>();
            for (var line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                headers.add(line);
            }
            return new RawResponse(status, headers);
        }
    }

    /** Строка статуса и заголовки ответа. */
    private record RawResponse(@Nullable String status, List<String> headers) {}
}
