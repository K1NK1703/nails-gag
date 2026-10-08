package gag.nails.identity.internal.session;

import gag.nails.common.error.InternalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Непрозрачные секреты входа: токены сессий, билеты регистрации, {@code state} и {@code codeVerifier}
 * PKCE. Случайные байты из {@link SecureRandom} в base64url без {@code =}; в базе хранится только
 * SHA-256 — утечка базы не даёт готовых токенов.
 */
public final class OpaqueTokens {

    /** Токены сессий, билеты и {@code state}: 256 бит случайности. */
    public static final int TOKEN_BYTES = 32;

    /** {@code codeVerifier} PKCE: 64 байта дают 86 символов, в пределах 43–128 по RFC 7636. */
    public static final int VERIFIER_BYTES = 64;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private OpaqueTokens() {}

    public static String random(int bytes) {
        var buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return BASE64URL.encodeToString(buffer);
    }

    public static String token() {
        return random(TOKEN_BYTES);
    }

    /** SHA-256 от строки в UTF-8 — ключ поиска в базе. */
    public static byte[] hash(String value) {
        return sha256().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code code_challenge} метода {@code S256}: base64url(SHA-256(verifier)). */
    public static String codeChallenge(String codeVerifier) {
        return BASE64URL.encodeToString(hash(codeVerifier));
    }

    /** Сравнение в постоянное время: длина и содержимое не выдают себя временем ответа. */
    public static boolean equal(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalException("SHA-256 is not available", e);
        }
    }
}
