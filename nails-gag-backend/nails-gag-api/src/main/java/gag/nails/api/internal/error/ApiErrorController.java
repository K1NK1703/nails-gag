package gag.nails.api.internal.error;

import jakarta.servlet.DispatcherType;
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
 * {@code /error} вместо стандартного контроллера Spring Boot: ошибки, которые контейнер переадресует сюда
 * ({@code response.sendError(...)} мимо исключений, исключение, вылетевшее из фильтра), тоже отвечают
 * {@link Problem}. Только статус и код по статусу — без сообщения, исключения и стека: причину в лог уже записал
 * контейнер. Запросы, которые Tomcat отвергает ещё до фильтров (кривой URI в {@code CoyoteAdapter}), сюда не
 * доходят и отвечают как решит Tomcat. Прямой запрос на {@code /error} — не переадресация ошибки, а адрес,
 * которого у API нет: {@code 404 common.not_found}.
 */
@RestController
class ApiErrorController implements ErrorController {

    private final ProblemWriter writer;

    ApiErrorController(ProblemWriter writer) {
        this.writer = writer;
    }

    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    ResponseEntity<Problem> error(HttpServletRequest request) {
        var status = request.getDispatcherType() == DispatcherType.ERROR ? status(request) : HttpStatus.NOT_FOUND;
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
