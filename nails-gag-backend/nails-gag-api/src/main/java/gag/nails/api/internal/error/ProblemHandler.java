package gag.nails.api.internal.error;

import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.UserFacingException;
import gag.nails.common.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.util.DisconnectedClientHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Ошибки контроллеров → {@link Problem} с {@code Content-Type: application/problem+json} (спецификация API,
 * §4.5–4.6). Ошибка модуля отвечает статусом из {@link ProblemStatuses} со своими {@code code} и {@code params};
 * нарушения формы запроса — {@code 400 common.validation} с перечнем {@code errors}; всё непредусмотренное —
 * {@code 500 common.internal} без подробностей, причина только в логе.
 *
 * <p>Не сотрудник (клиент или аноним) не должен отличать чужое от несуществующего: любой его {@code 404} и
 * {@link AccessDeniedException} вошедшего клиента отвечают {@code 404 common.not_found} без параметров. Сотрудник
 * видит точный код и параметры.
 *
 * <p>Логи: ошибки модулей и формы запроса — WARN без стека и без значений {@code params} (в них бывают телефоны),
 * только код и имена параметров; недоступность БД из-за блокировки — WARN; непредусмотренное — ERROR со стеком.
 * Отказы самого Spring MVC (нет адреса, не тот метод) и оборванные клиентом соединения — DEBUG: в WARN они бы
 * заглушили полезное.
 */
@RestControllerAdvice
class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    static final String UNKNOWN_PROPERTY = "unknown property";
    static final String INVALID_VALUE = "invalid value";
    static final String REQUIRED = "required";

    /** Поле ошибки уровня всего тела (ограничение на классе, а не на поле). */
    static final String BODY = "body";

    /** Через сколько секунд повторить запрос, упавший на блокировке в БД. */
    static final String BUSY_RETRY_AFTER_SECONDS = "1";

    private final ProblemWriter writer;

    ProblemHandler(ProblemWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(UserFacingException.class)
    ResponseEntity<Problem> userFacing(UserFacingException exception, HttpServletRequest request) {
        var status = ProblemStatuses.of(exception);
        if (status.isEmpty()) {
            log.error(
                    "{} ({}) has no HTTP status in ProblemStatuses",
                    exception.getClass().getName(),
                    exception.code());
            return internal(request);
        }
        if (exception instanceof ValidationException validation) {
            logUserFacing(request, HttpStatus.BAD_REQUEST, exception);
            return validation(request, List.of(new Problem.FieldError(validation.field(), validation.reason())));
        }
        var staff = ApiRequestAttributes.authenticated(request)
                .map(authenticated -> authenticated.actor().isStaff())
                .orElse(false);
        if (exception instanceof AccessDeniedException) {
            return accessDenied(exception, request, staff);
        }
        logUserFacing(request, status.get(), exception);
        if (status.get() == HttpStatus.NOT_FOUND && !staff) {
            return respond(writer.problem(request, HttpStatus.NOT_FOUND, ProblemStatuses.NOT_FOUND));
        }
        return respond(
                writer.problem(request.getRequestURI(), status.get(), exception.code(), exception.params(), List.of()));
    }

    /**
     * Нет сессии — {@code 401}; сотрудник — честный {@code 403} про раздел; клиент — {@code 404}, чтобы чужая запись
     * не отличалась от несуществующей (§4.6).
     */
    private ResponseEntity<Problem> accessDenied(
            UserFacingException exception, HttpServletRequest request, boolean staff) {
        HttpStatus status;
        String code;
        if (ApiRequestAttributes.authenticated(request).isEmpty()) {
            status = HttpStatus.UNAUTHORIZED;
            code = ProblemStatuses.UNAUTHENTICATED;
        } else if (staff) {
            status = HttpStatus.FORBIDDEN;
            code = ProblemStatuses.ACCESS_DENIED;
        } else {
            status = HttpStatus.NOT_FOUND;
            code = ProblemStatuses.NOT_FOUND;
        }
        logUserFacing(request, status, exception);
        return respond(writer.problem(request, status, code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getAllErrors().stream()
                .map(ProblemHandler::fieldError)
                .toList();
        logInvalid(request, errors);
        return validation(request, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Problem> invalidParameters(HandlerMethodValidationException exception, HttpServletRequest request) {
        var errors = new ArrayList<Problem.FieldError>();
        for (var result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getAllErrors().forEach(error -> errors.add(fieldError(error)));
            } else {
                var name = requestName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new Problem.FieldError(name, reason(error.getDefaultMessage())));
                }
            }
        }
        logInvalid(request, errors);
        return validation(request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> invalidConstraints(ConstraintViolationException exception, HttpServletRequest request) {
        var errors = exception.getConstraintViolations().stream()
                .map(violation -> new Problem.FieldError(leafName(violation), reason(violation.getMessage())))
                .toList();
        logInvalid(request, errors);
        return validation(request, errors);
    }

    /**
     * Нечитаемое тело. Если Jackson знает место ошибки — одно нарушение с путём через точку ({@code items.0.time});
     * иначе (битый JSON, пустое тело) — без {@code errors}.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> unreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        var errors = new ArrayList<Problem.FieldError>();
        var jackson = jacksonCause(exception);
        if (jackson != null) {
            var path = jackson.getPath().stream()
                    .map(ProblemHandler::pathElement)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!path.isEmpty()) {
                var reason = jackson instanceof UnrecognizedPropertyException ? UNKNOWN_PROPERTY : INVALID_VALUE;
                errors.add(new Problem.FieldError(path, reason));
            }
        }
        logInvalid(request, errors);
        return validation(request, errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Problem> missingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        var errors = List.of(new Problem.FieldError(exception.getParameterName(), REQUIRED));
        logInvalid(request, errors);
        return validation(request, errors);
    }

    /** {@code getName()} — имя из {@code @RequestParam}/{@code @PathVariable}, а не имя параметра в Java. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> mismatchedParameter(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        var errors = List.of(new Problem.FieldError(exception.getName(), INVALID_VALUE));
        logInvalid(request, errors);
        return validation(request, errors);
    }

    /**
     * Взаимная блокировка или таймаут блокировки в PostgreSQL. Автоматического повтора нет: обратный вызов
     * провайдера повторить нельзя, остальное фронт повторит сам, когда это безопасно.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Problem> busy(PessimisticLockingFailureException exception, HttpServletRequest request) {
        log.warn(
                "{} {} -> 503 database lock failure: {}",
                request.getMethod(),
                request.getRequestURI(),
                exception.getMessage());
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS);
        return respond(writer.problem(request, HttpStatus.SERVICE_UNAVAILABLE, ProblemStatuses.BUSY), headers);
    }

    /**
     * Прочие исключения Spring MVC со статусом ({@code 404}, {@code 405}, {@code 415}, {@code 503}…) — их статус и
     * код слоя по нему; всё непредусмотренное — {@code 500}. Клиент оборвал соединение — отвечать некому: только
     * DEBUG, ответ считается обработанным.
     */
    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Problem> other(Exception exception, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            log.debug(
                    "{} {}: client disconnected: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    exception.toString());
            return null;
        }
        if (exception instanceof ErrorResponse response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("{} {} failed", request.getMethod(), request.getRequestURI(), exception);
            } else {
                log.debug(
                        "{} {} -> {} {}",
                        request.getMethod(),
                        request.getRequestURI(),
                        status.value(),
                        exception.toString());
            }
            var headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            return respond(writer.problem(request, status, ProblemStatuses.codeOf(status)), headers);
        }
        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), exception);
        return internal(request);
    }

    private ResponseEntity<Problem> internal(HttpServletRequest request) {
        return respond(writer.problem(request, HttpStatus.INTERNAL_SERVER_ERROR, ProblemStatuses.INTERNAL));
    }

    private ResponseEntity<Problem> validation(HttpServletRequest request, List<Problem.FieldError> errors) {
        var sorted = errors.stream()
                .sorted(Comparator.comparing(Problem.FieldError::field).thenComparing(Problem.FieldError::reason))
                .toList();
        return respond(writer.problem(
                request.getRequestURI(), HttpStatus.BAD_REQUEST, ProblemStatuses.VALIDATION, Map.of(), sorted));
    }

    private static ResponseEntity<Problem> respond(Problem problem) {
        return respond(problem, new HttpHeaders());
    }

    private static ResponseEntity<Problem> respond(Problem problem, HttpHeaders headers) {
        return ResponseEntity.status(problem.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** Только код и имена параметров: значения ({@code phone}, {@code clientId}…) в лог не попадают. */
    private static void logUserFacing(
            HttpServletRequest request, HttpStatusCode status, UserFacingException exception) {
        log.warn(
                "{} {} -> {} {} {}",
                request.getMethod(),
                request.getRequestURI(),
                status.value(),
                exception.code(),
                exception.params().keySet());
    }

    private static void logInvalid(HttpServletRequest request, List<Problem.FieldError> errors) {
        log.warn("{} {} -> 400 {}", request.getMethod(), request.getRequestURI(), errors);
    }

    private static Problem.FieldError fieldError(ObjectError error) {
        var field = error instanceof FieldError fieldError ? fieldError.getField() : BODY;
        return new Problem.FieldError(field, reason(error.getDefaultMessage()));
    }

    private static String reason(@Nullable String message) {
        return message == null ? INVALID_VALUE : message;
    }

    /** Имя параметра, каким его видит клиент: из {@code @RequestParam}/{@code @PathVariable}/{@code @RequestHeader}. */
    private static String requestName(MethodParameter parameter) {
        var requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return firstNonEmpty(requestParam.name(), requestParam.value(), parameter);
        }
        var pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return firstNonEmpty(pathVariable.name(), pathVariable.value(), parameter);
        }
        var requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return firstNonEmpty(requestHeader.name(), requestHeader.value(), parameter);
        }
        return javaName(parameter);
    }

    private static String firstNonEmpty(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? javaName(parameter) : value;
    }

    private static String javaName(MethodParameter parameter) {
        return Objects.requireNonNullElse(parameter.getParameterName(), "arg" + parameter.getParameterIndex());
    }

    /** {@code list.from} → {@code from}: имя параметра или поля, на котором сработало ограничение. */
    private static String leafName(ConstraintViolation<?> violation) {
        String name = null;
        for (var node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name == null ? violation.getPropertyPath().toString() : name;
    }

    /** Свойство — по имени, элемент массива — по номеру; ссылка без имени и номера пропускается. */
    private static @Nullable String pathElement(JacksonException.Reference reference) {
        if (reference.getPropertyName() != null) {
            return reference.getPropertyName();
        }
        return reference.getIndex() >= 0 ? String.valueOf(reference.getIndex()) : null;
    }

    private static @Nullable JacksonException jacksonCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson) {
                return jackson;
            }
        }
        return null;
    }
}
