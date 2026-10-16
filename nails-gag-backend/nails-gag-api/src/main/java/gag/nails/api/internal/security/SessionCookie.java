package gag.nails.api.internal.security;

import gag.nails.api.internal.ApiProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
public final class SessionCookie {

    static final String NAME = "ng_session";

    static final String PATH = "/api";
    static final Duration MAX_AGE = Duration.ofDays(90);

    /** Сколько значений cookie с этим именем проверяется по базе, не больше. */
    static final int MAX_CANDIDATES = 2;

    /**
     * Форма токена сессии и билета входа ({@code OpaqueTokens.token()} модуля входа): 32 случайных байта в base64url
     * без дополнения — 43 символа.
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final boolean secure;

    SessionCookie(ApiProperties properties) {
        this.secure = properties.cookies().secure();
    }

    /**
     * Похоже ли значение на токен {@code Sessions} или билет {@code LoginFlow}. Значение другой формы в базу не идёт —
     * оно заведомо не годно.
     */
    public static boolean tokenShaped(@Nullable String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    /** Есть ли в запросе cookie сессии — годная или нет. */
    boolean sent(HttpServletRequest request) {
        var cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies).anyMatch(cookie -> NAME.equals(cookie.getName()));
    }

    /**
     * Значения cookie сессии, похожие на токен {@code Sessions} (32 байта в base64url без {@code =}), в порядке
     * запроса и не больше {@value #MAX_CANDIDATES}: соседний поддомен может подбросить cookie с тем же именем, и
     * браузер пришлёт обе. Значение другой формы в базу не идёт — оно заведомо не сессия.
     */
    List<String> candidates(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(SessionCookie::tokenShaped)
                .limit(MAX_CANDIDATES)
                .toList();
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
