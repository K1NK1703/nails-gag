package gag.nails.api.internal.security;

import gag.nails.api.internal.ApiProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie сессии {@value #NAME} (спецификация API, §5.1) — единственное место, где знают её имя и атрибуты:
 * {@code HttpOnly}, {@code Secure} из {@code nails-gag.api.cookies.secure}, {@code SameSite=Lax} (обратный вызов
 * провайдера — межсайтовый переход, и для привязки cookie должна дойти), {@code Path=/api}, {@code Max-Age} 90 дней —
 * абсолютный срок сессии; простой и досрочное закрытие решает сервер. Публичный: cookie ставят и снимают адреса
 * входа и выхода.
 */
@Component
public class SessionCookie {

    public static final String NAME = "ng_session";

    static final String PATH = "/api";
    static final Duration MAX_AGE = Duration.ofDays(90);

    private final boolean secure;

    SessionCookie(ApiProperties properties) {
        this.secure = properties.cookies().secure();
    }

    /** Токен из cookie запроса; пустое значение — как отсутствие. */
    public Optional<String> read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isEmpty())
                .findFirst();
    }

    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, MAX_AGE).toString());
    }

    /** Та же cookie с пустым значением и {@code Max-Age=0}: браузер её удаляет. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
