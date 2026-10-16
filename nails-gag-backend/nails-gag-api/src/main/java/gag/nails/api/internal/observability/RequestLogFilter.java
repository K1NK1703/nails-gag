package gag.nails.api.internal.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Одна строка INFO на запрос {@code /api/*} после всей цепочки: {@code "GET /api/bookings/{id} -> 200 12ms"}. Адрес —
 * шаблон метода контроллера, который выбрал {@code DispatcherServlet}, без значений из пути; если запрос до
 * контроллера не дошёл (отказ лимита или CSRF стоит дальше в цепочке, адрес неизвестен) — путь запроса. Строка
 * запроса не пишется никогда: в поиске там бывает телефон. Стоит сразу после фильтра сессии, поэтому у вошедшего в
 * строке есть {@code accountId} из MDC.
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            var millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("{} {} -> {} {}ms", request.getMethod(), route(request), response.getStatus(), millis);
        }
    }

    /**
     * Шаблон адреса, если запрос сопоставлен методу контроллера, иначе путь запроса; {@code getRequestURI()} строки
     * запроса не несёт. Неизвестный адрес Spring Boot отдаёт обработчику статики с шаблоном {@code /**} — такой
     * шаблон ничего не говорит, поэтому учитывается только {@link HandlerMethod}.
     */
    private static String route(HttpServletRequest request) {
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        var handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        return pattern != null && handler instanceof HandlerMethod ? pattern.toString() : request.getRequestURI();
    }
}
