package gag.nails.api.internal.auth;

import gag.nails.api.internal.ApiProperties;
import gag.nails.api.internal.security.SessionCookie;
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
 * Cookie {@value #NAME} (спецификация API, §6.1) — билет регистрации или привязки из {@code LoginFlow.complete}. Билет
 * уже случайный и в базе хранится хешем, поэтому лежит как есть; в URL он не попадает никогда. Атрибуты:
 * {@code HttpOnly}, {@code Secure} из настроек, {@code SameSite=Lax}, {@code Path=/api/auth}, {@code Max-Age}
 * 30 минут — столько живёт билет. Значение не логируется.
 */
@Component
final class TicketCookie {

    static final String NAME = "ng_ticket";
    static final String PATH = "/api/auth";
    static final Duration MAX_AGE = Duration.ofMinutes(30);

    private final boolean secure;

    TicketCookie(ApiProperties properties) {
        this.secure = properties.cookies().secure();
    }

    /**
     * Первое значение cookie в форме билета ({@link SessionCookie#tokenShaped}); значение другой формы — как будто
     * cookie нет. Годен ли билет, решает модуль.
     */
    Optional<String> read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(SessionCookie::tokenShaped)
                .findFirst();
    }

    /** Есть ли в запросе cookie билета — годная или нет. */
    boolean sent(HttpServletRequest request) {
        var cookies = request.getCookies();
        return cookies != null && Arrays.stream(cookies).anyMatch(cookie -> NAME.equals(cookie.getName()));
    }

    void write(HttpServletResponse response, String ticket) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ticket, MAX_AGE).toString());
    }

    /** Та же cookie с пустым значением и {@code Max-Age=0}: браузер её удаляет. */
    void clear(HttpServletResponse response) {
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
