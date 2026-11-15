package gag.nails.api.internal.analytics;

import gag.nails.analytics.AnalyticsIngest;
import gag.nails.analytics.command.RecordEvent;
import gag.nails.api.internal.http.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.MutableCoercionConfig;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Приём событий сайта для счётчика посещений (спецификация счётчика, §4). Ответ — всегда {@code 204} без тела, что бы
 * ни пришло: боту нечего узнать из ответа. Отказы до контроллера — только у фильтров: {@code 403 common.csrf} (источник
 * — не сайт; служебного заголовка этот адрес не ждёт, его не умеет ставить {@code sendBeacon}) и
 * {@code 429 common.rate_limited} (своя корзина по IP). Сессия не нужна и не используется.
 *
 * <p>Тело читается здесь, а не через {@code @RequestBody}: любой сбой разбора должен давать {@code 204}, а не
 * {@code 400}, и тип содержимого не проверяется — {@code sendBeacon} шлёт {@code application/json} (Blob фронта) или
 * {@code text/plain} (строка), а может и без заголовка; тело в любом случае разбирается как JSON.
 *
 * <p>Разбор терпим только к незнакомым полям (время {@code ts} фронта и любые другие пропускаются), в остальном строг:
 * значение знакомого поля — ровно своего вида JSON (строка — строкой, {@code index} — целым числом, {@code entry} —
 * {@code true}/{@code false}), без приведений вроде {@code "7"} → {@code 7}, {@code 7.9} → {@code 7} или {@code 1} →
 * {@code true}; повтор поля и что-либо после объекта — ошибка, а не «последнее значение»: такое тело собрано не
 * фронтом.
 * Ошибка разбора — событие не считается. Сами виды событий и значения проверяет модуль.
 *
 * <p>Ресурсы. Читается не больше {@value #MAX_BODY_BYTES} байт и ещё одного: заявленный {@code Content-Length} может
 * врать или отсутствовать ({@code Transfer-Encoding: chunked}); заявленное больше предела не читается вовсе. На тело
 * больше предела ответ {@code 204} уходит сразу, с {@code Connection: close}. Но после ответа Tomcat всё равно
 * дочитывает и отбрасывает остаток тела — до {@code server.tomcat.max-swallow-size} (2 МБ по умолчанию; сверх того
 * закрывает соединение), и соединение живёт, пока байты идут: каждое чтение ждёт до {@code connection-timeout}.
 * Медленное или огромное тело отсюда не остановить — от этого защищает прокси (предел тела, буферизация запроса,
 * таймауты), а не приложение.
 *
 * <p>IP посетителя — адрес клиента, который Tomcat уже взял из {@code X-Forwarded-For} доверенного прокси (спецификация
 * API, §5.4). IP и {@code User-Agent} уходят только в модуль (для суточного отпечатка) и нигде не пишутся в лог.
 */
@RestController
class AnalyticsEventsController {

    /** Самое большое тело события: событие фронта занимает около сотни байт. */
    static final int MAX_BODY_BYTES = 1024;

    private final AnalyticsIngest ingest;
    private final JsonMapper events;

    AnalyticsEventsController(AnalyticsIngest ingest, JsonMapper applicationMapper) {
        this.ingest = ingest;
        this.events = applicationMapper
                .rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(
                        LogicalType.Textual,
                        config -> fail(
                                config,
                                CoercionInputShape.Integer,
                                CoercionInputShape.Float,
                                CoercionInputShape.Boolean))
                .withCoercionConfig(
                        LogicalType.Integer,
                        config -> fail(
                                config,
                                CoercionInputShape.String,
                                CoercionInputShape.EmptyString,
                                CoercionInputShape.Float,
                                CoercionInputShape.Boolean))
                .withCoercionConfig(
                        LogicalType.Boolean,
                        config -> fail(
                                config,
                                CoercionInputShape.String,
                                CoercionInputShape.EmptyString,
                                CoercionInputShape.Integer,
                                CoercionInputShape.Float))
                .build();
    }

    /**
     * Прибавляет событие к счётчикам дня. Тело — JSON-объект события: {@code name} и поля его вида ({@code path},
     * {@code place}, {@code channel}, {@code index}, {@code id}, {@code entry}, {@code referrer} — их правила описаны
     * у {@link RecordEvent}); незнакомые поля пропускаются. Тело больше {@value #MAX_BODY_BYTES} байт, не JSON, не
     * объект, повтор поля, значение не того вида JSON или неверное событие — молча {@code 204}, ничего не считается;
     * робот по {@code User-Agent} — так же.
     */
    @PostMapping(ApiPaths.ANALYTICS_EVENTS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void record(HttpServletRequest request, HttpServletResponse response) {
        var body = body(request, response);
        if (body == null) {
            return;
        }
        var event = parse(body);
        if (event == null) {
            return;
        }
        ingest.record(
                event,
                Objects.requireNonNullElse(request.getRemoteAddr(), ""),
                request.getHeader(HttpHeaders.USER_AGENT));
    }

    /**
     * Тело запроса, если оно не больше {@value #MAX_BODY_BYTES} байт; иначе {@code null} и ответ с
     * {@code Connection: close}. Заявленное больше предела не читается вовсе, остальное — не больше предела и байта.
     * Обрыв чтения (клиент ушёл, тело не пришло вовремя) — тоже {@code null}: событие просто не считается.
     */
    private static byte @Nullable [] body(HttpServletRequest request, HttpServletResponse response) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return tooLarge(response);
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            return null;
        }
        return body.length > MAX_BODY_BYTES ? tooLarge(response) : body;
    }

    private static byte @Nullable [] tooLarge(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CONNECTION, "close");
        return null;
    }

    /** Значения этих видов JSON не приводятся к типу поля, а дают ошибку разбора. */
    private static void fail(MutableCoercionConfig config, CoercionInputShape... shapes) {
        for (var shape : shapes) {
            config.setCoercion(shape, CoercionAction.Fail);
        }
    }

    /**
     * Событие из тела; не JSON, не объект, лишнее после объекта, повтор поля или значение не того вида JSON —
     * {@code null}.
     */
    private @Nullable RecordEvent parse(byte[] body) {
        try {
            return events.readValue(body, RecordEvent.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}
