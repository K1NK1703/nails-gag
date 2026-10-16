package gag.nails.api.internal.auth;

import gag.nails.api.internal.ApiProperties;
import gag.nails.common.error.InternalException;
import gag.nails.identity.model.LoginState;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cookie этапа входа {@value #NAME} (спецификация API, §6.1): {@link LoginState} между {@code start} и обратным
 * вызовом провайдера. В состоянии — {@code codeVerifier} PKCE и аккаунт привязки, которому модуль доверяет, поэтому
 * значение зашифровано AES-256-GCM серверным ключом: прочитать его нельзя, подделать — тоже (не сойдётся тег).
 *
 * <p>Значение — base64url без дополнения от {@code IV (12 байт) ‖ шифртекст+тег (128 бит)}, дополнительные
 * аутентифицированные данные — имя cookie, открытый текст — JSON состояния. Ключ —
 * {@code nails-gag.api.login-state-key}; пустой (только вне {@code prod}, это проверяет {@code ApiPropertiesGuard}) —
 * случайный на время работы процесса.
 * Атрибуты: {@code HttpOnly}, {@code Secure} из настроек, {@code SameSite=Lax} (обратный вызов — межсайтовый
 * переход), {@code Path=/api/auth}, {@code Max-Age} 10 минут — столько живёт состояние. Значение не логируется.
 */
@Component
final class LoginStateCookie {

    private static final Logger log = LoggerFactory.getLogger(LoginStateCookie.class);

    static final String NAME = "ng_login";
    static final String PATH = "/api/auth";
    static final Duration MAX_AGE = Duration.ofMinutes(10);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = NAME.getBytes(StandardCharsets.US_ASCII);

    /** Сколько значений cookie с этим именем пробуется расшифровать, не больше. */
    private static final int MAX_CANDIDATES = 2;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final boolean secure;
    private final JsonMapper mapper;

    LoginStateCookie(ApiProperties properties, JsonMapper mapper) {
        this.key = new SecretKeySpec(keyBytes(properties.loginStateKey()), ALGORITHM);
        this.secure = properties.cookies().secure();
        this.mapper = mapper;
    }

    /**
     * Состояние из cookie запроса. Пусто, если cookie нет или ни одно из первых {@value #MAX_CANDIDATES} значений не
     * расшифровалось: неверный base64, короткое значение, не сошёлся тег, не разобрался JSON. Не бросает: значение
     * прислал браузер.
     */
    Optional<LoginState> read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .limit(MAX_CANDIDATES)
                .map(this::decrypt)
                .flatMap(Optional::stream)
                .findFirst();
    }

    void write(HttpServletResponse response, LoginState state) {
        response.addHeader(
                HttpHeaders.SET_COOKIE, cookie(encrypt(state), MAX_AGE).toString());
    }

    /** Та же cookie с пустым значением и {@code Max-Age=0}: браузер её удаляет. */
    void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private String encrypt(LoginState state) {
        var iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] sealed;
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            sealed = cipher.doFinal(mapper.writeValueAsBytes(state));
        } catch (GeneralSecurityException e) {
            throw new InternalException("login state encryption failed", e);
        }
        var value = ByteBuffer.allocate(IV_BYTES + sealed.length)
                .put(iv)
                .put(sealed)
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private Optional<LoginState> decrypt(String value) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (bytes.length <= IV_BYTES + TAG_BITS / Byte.SIZE) {
            return Optional.empty();
        }
        byte[] json;
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            cipher.updateAAD(AAD);
            json = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            return Optional.empty();
        } catch (GeneralSecurityException e) {
            throw new InternalException("login state decryption failed", e);
        }
        try {
            return Optional.ofNullable(mapper.readValue(json, LoginState.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
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

    /** Ключ из настроек; форму при старте уже проверил {@code ApiPropertiesGuard}, здесь — на случай порядка бинов. */
    private byte[] keyBytes(String configured) {
        if (configured.isBlank()) {
            log.warn("nails-gag.api.login-state-key is empty: using a random key, sign-ins in progress will not"
                    + " survive a restart");
            var bytes = new byte[KEY_BYTES];
            random.nextBytes(bytes);
            return bytes;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            throw new InternalException("nails-gag.api.login-state-key is not valid base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new InternalException("nails-gag.api.login-state-key must decode to exactly " + KEY_BYTES + " bytes");
        }
        return bytes;
    }
}
