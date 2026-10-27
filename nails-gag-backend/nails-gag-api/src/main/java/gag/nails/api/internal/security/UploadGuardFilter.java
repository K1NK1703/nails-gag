package gag.nails.api.internal.security;

import gag.nails.api.internal.error.ProblemWriter;
import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.common.actor.Section;
import gag.nails.files.error.TooLargeFileException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ранние отказы загрузки фото ({@code POST /api/files}, спецификация файлов и портфолио, §6.1) — до чтения тела:
 * multipart разбирается лениво, и без этого фильтра сессию и права проверил бы только контроллер, когда Tomcat уже
 * записал до {@code max-request-size} во временный файл. По порядку: нет сессии — {@code 401 common.unauthenticated};
 * {@code purpose=portfolio} без изменения «Портфолио» — сотруднику {@code 403 common.access_denied}, остальным
 * {@code 404 common.not_found} (спецификация API, §4.6); {@code Content-Length} больше {@code max-request-size} —
 * {@code 413 files.too_large_file} с пределом файла.
 *
 * <p>Назначение читается из строки запроса, а не {@code getParameter}: тот у multipart-запроса разбирает и тело. Те же
 * проверки повторяет контроллер — второй барьер; неизвестное или отсутствующее назначение и тело без длины
 * (по частям) пропускаются: их разберёт он, а длину ограничит Tomcat.
 */
class UploadGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadGuardFilter.class);

    static final String UNAUTHENTICATED = "common.unauthenticated";
    static final String ACCESS_DENIED = "common.access_denied";
    static final String NOT_FOUND = "common.not_found";

    /** Назначение загрузки, для которого нужны права, — как его пишет адрес. */
    private static final String PORTFOLIO = "portfolio";

    private static final String PURPOSE = "purpose";

    private final ProblemWriter problems;
    private final @Nullable MultipartConfigElement multipart;

    /** @param multipart пределы multipart сервлета; без них длина тела здесь не проверяется */
    UploadGuardFilter(ProblemWriter problems, @Nullable MultipartConfigElement multipart) {
        this.problems = problems;
        this.multipart = multipart;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RateLimitFilter.isUpload(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authenticated = ApiRequestAttributes.authenticated(request);
        if (authenticated.isEmpty()) {
            refuse(request, response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED);
            return;
        }
        var actor = authenticated.get().actor();
        if (PORTFOLIO.equals(purpose(request)) && !actor.canWrite(Section.PORTFOLIO)) {
            if (actor.isStaff()) {
                refuse(request, response, HttpStatus.FORBIDDEN, ACCESS_DENIED);
            } else {
                refuse(request, response, HttpStatus.NOT_FOUND, NOT_FOUND);
            }
            return;
        }
        if (multipart != null
                && multipart.getMaxRequestSize() > 0
                && request.getContentLengthLong() > multipart.getMaxRequestSize()) {
            var tooLarge = new TooLargeFileException(multipart.getMaxFileSize());
            log.warn(
                    "{} {} -> 413 {} by Content-Length", request.getMethod(), request.getRequestURI(), tooLarge.code());
            problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, tooLarge);
            return;
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        log.warn(
                "{} {} -> {} {} before reading the upload",
                request.getMethod(),
                request.getRequestURI(),
                status.value(),
                code);
        problems.write(request, response, status, code);
    }

    /**
     * Первое значение {@code purpose} строки запроса, как его разобрал бы Spring; нет — {@code null}. Регистр не
     * меняется: {@code PORTFOLIO} — неверное значение, его отвергнет контроллер.
     */
    private static @Nullable String purpose(HttpServletRequest request) {
        var query = request.getQueryString();
        if (query == null) {
            return null;
        }
        for (var pair : query.split("&")) {
            var separator = pair.indexOf('=');
            var name = separator < 0 ? pair : pair.substring(0, separator);
            if (PURPOSE.equals(decode(name))) {
                return separator < 0 ? "" : decode(pair.substring(separator + 1));
            }
        }
        return null;
    }

    /** Битое процентное кодирование остаётся как есть: такое значение отвергнет контроллер. */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
