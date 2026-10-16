package gag.nails.api.internal.error;

import gag.nails.api.internal.http.JsonConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Собирает {@link Problem} и пишет его в ответ. Публичный: фильтры защиты работают до {@code DispatcherServlet},
 * и {@code @RestControllerAdvice} их отказов ({@code 401}, {@code 403 common.csrf}, {@code 429}) не видит.
 * Тело пишется тем же mapper-ом, что и ответы контроллеров.
 */
@Component
public class ProblemWriter {

    private static final Logger log = LoggerFactory.getLogger(ProblemWriter.class);

    /** Ключ MDC, куда трассировка Spring Boot кладёт идентификатор трассы. */
    static final String TRACE_ID = "traceId";

    private final JsonConfiguration json;

    ProblemWriter(JsonConfiguration json) {
        this.json = json;
    }

    public Problem problem(HttpServletRequest request, HttpStatusCode status, String code) {
        return problem(request.getRequestURI(), status, code, Map.of(), List.of());
    }

    public Problem problem(
            String instance,
            HttpStatusCode status,
            String code,
            Map<String, Object> params,
            List<Problem.FieldError> errors) {
        var known = HttpStatus.resolve(status.value());
        return new Problem(
                Problem.ABOUT_BLANK,
                known == null ? null : known.getReasonPhrase(),
                status.value(),
                instance,
                code,
                params,
                errors,
                MDC.get(TRACE_ID));
    }

    /** Пишет отказ фильтра; заголовки вроде {@code Retry-After} фильтр ставит сам до вызова. */
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status, String code)
            throws IOException {
        write(response, problem(request, status, code));
    }

    /**
     * Пишет тело, если ответ ещё не начат: уже отправленные заголовки не перепишешь, тогда только DEBUG. Начатое, но не
     * отправленное тело сбрасывается. JSON сначала собирается в байты — Jackson не закрывает поток контейнера.
     */
    public void write(HttpServletResponse response, Problem problem) throws IOException {
        if (response.isCommitted()) {
            log.debug("Response for {} already committed, problem {} not written", problem.instance(), problem.code());
            return;
        }
        response.resetBuffer();
        var body = json.mapper().writeValueAsBytes(problem);
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
