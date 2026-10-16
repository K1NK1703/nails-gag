package gag.nails.api.internal.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /error} вместо стандартного контроллера Spring Boot: ошибки, не дошедшие до {@code DispatcherServlet}
 * (отказ Tomcat, исключение вне наших фильтров), тоже отвечают {@link Problem}. Только статус и код по
 * статусу — без сообщения, исключения и стека: причину в лог уже записал контейнер.
 */
@RestController
class ApiErrorController implements ErrorController {

    private final ProblemWriter writer;

    ApiErrorController(ProblemWriter writer) {
        this.writer = writer;
    }

    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    ResponseEntity<Problem> error(HttpServletRequest request) {
        var status = status(request);
        var instance = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
                ? uri
                : request.getRequestURI();
        var problem = writer.problem(instance, status, ProblemStatuses.codeOf(status), Map.of(), List.of());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static HttpStatusCode status(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code && code >= 400) {
            return HttpStatusCode.valueOf(code);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
