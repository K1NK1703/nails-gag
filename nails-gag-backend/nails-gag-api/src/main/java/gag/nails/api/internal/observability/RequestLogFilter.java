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
 * Одна строка INFO на запрос {@code /api/*} после всей цепочки: {@code "GET /api/bookings/{id} -> 200 12ms"}.
 *
 * <ul>
 *   <li>Адрес — шаблон метода контроллера, который выбрал {@code DispatcherServlet}, без значений из пути. Если запрос
 *       до контроллера не дошёл (отказ лимита или CSRF дальше в цепочке, неизвестный адрес) — путь запроса как есть,
 *       идентификаторы в нём остаются; путь задаёт клиент, поэтому длиннее {@value #MAX_PATH_LENGTH} символов он
 *       обрезается. Строка запроса не пишется никогда: в поиске там бывает телефон.
 *   <li>Статус — из ответа; если исключение ушло из цепочки, {@code 500}: такой статус Tomcat выставит позже, а в
 *       ответе к этому моменту ещё стоит {@code 200} по умолчанию.
 *   <li>Время — от входа в этот фильтр: фильтры заголовков и сессии (с проверкой сессии в базе) в него не входят.
 *   <li>Пишется только исходный проход запроса ({@code REQUEST}); повторные проходы для страницы ошибки и
 *       асинхронные {@link OncePerRequestFilter} пропускает.
 * </ul>
 *
 * <p>Стоит сразу после фильтра сессии, поэтому у вошедшего в строке есть {@code accountId} из MDC.
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /** Сколько символов пути попадает в строку, не больше: несопоставленный адрес задаёт клиент. */
    static final int MAX_PATH_LENGTH = 200;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var started = System.nanoTime();
        var failed = false;
        try {
            chain.doFilter(request, response);
        } catch (Throwable e) {
            failed = true;
            throw e;
        } finally {
            var millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            var status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            log.info("{} {} -> {} {}ms", request.getMethod(), route(request), status, millis);
        }
    }

    /**
     * Шаблон адреса, если запрос сопоставлен методу контроллера, иначе путь запроса, обрезанный до
     * {@value #MAX_PATH_LENGTH} символов; {@code getRequestURI()} строки запроса не несёт. Неизвестный адрес Spring Boot
     * отдаёт обработчику статики с шаблоном {@code /**} — такой шаблон ничего не говорит, поэтому учитывается только
     * {@link HandlerMethod}.
     */
    private static String route(HttpServletRequest request) {
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        var handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (pattern != null && handler instanceof HandlerMethod) {
            return pattern.toString();
        }
        var path = request.getRequestURI();
        return path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) + "…" : path;
    }
}
