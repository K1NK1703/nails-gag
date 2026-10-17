package gag.nails.api.internal.observability;

import io.micrometer.common.KeyValue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Ключи и разметка запроса для логов и трасс: кто сделал запрос. {@code accountId} в MDC попадает в каждую строку
 * журнала, пока идёт запрос, и в записи OTLP (мост Logback в {@code app}); {@code account.id} — high-cardinality
 * атрибут наблюдения HTTP-запроса: он уходит в span, но не в метрики, где число аккаунтов раздуло бы ряды.
 */
public final class RequestTelemetry {

    /** Ключ MDC с идентификатором аккаунта вошедшего. */
    public static final String MDC_ACCOUNT_ID = "accountId";

    /** Атрибут span-а HTTP-запроса с идентификатором аккаунта: в Tempo — {@code { span.account.id = "…" }}. */
    public static final String SPAN_ACCOUNT_ID = "account.id";

    private RequestTelemetry() {}

    /**
     * Кладёт {@code accountId} в MDC и high-cardinality атрибут в контекст наблюдения запроса, если он есть (без
     * Actuator наблюдения нет — тогда только MDC). Закрытие снимает MDC; атрибут остаётся до конца наблюдения.
     */
    public static MDC.MDCCloseable tagAccount(HttpServletRequest request, UUID accountId) {
        var id = accountId.toString();
        ServerHttpObservationFilter.findObservationContext(request)
                .ifPresent(context -> context.addHighCardinalityKeyValue(KeyValue.of(SPAN_ACCOUNT_ID, id)));
        return MDC.putCloseable(MDC_ACCOUNT_ID, id);
    }
}
