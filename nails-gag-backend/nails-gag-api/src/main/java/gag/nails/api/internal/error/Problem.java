package gag.nails.api.internal.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Тело ответа об ошибке: RFC 9457 и поля слоя. Одна форма для обработчика контроллеров, фильтров и
 * {@code /error}. Пустые {@code params}/{@code errors} и отсутствующие {@code title}/{@code traceId} не пишутся.
 *
 * @param type всегда {@code about:blank}: смысл ошибки несёт {@code code}
 * @param title стандартная фраза статуса ({@code Conflict})
 * @param instance путь запроса
 * @param code постоянный код ({@code booking.limit_reached}); по нему фронт выбирает текст
 * @param params параметры для подстановки в текст
 * @param errors нарушения по полям, только у {@code common.validation}
 * @param traceId идентификатор трассы из MDC; по нему ошибку находят в логах
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Problem(
        String type,
        @Nullable String title,
        int status,
        String instance,
        String code,
        Map<String, Object> params,
        List<FieldError> errors,
        @Nullable String traceId) {

    public static final String ABOUT_BLANK = "about:blank";

    public Problem {
        params = Map.copyOf(params);
        errors = List.copyOf(errors);
    }

    /** Нарушение: {@code field} — путь к полю тела через точку или имя параметра запроса. */
    public record FieldError(String field, String reason) {}
}
