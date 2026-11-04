package gag.nails.analytics.internal.visitor;

import gag.nails.common.error.InternalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Суточный отпечаток посетителя: {@code SHA-256(соль дня ‖ ip ‖ 0x00 ‖ userAgent)}, строки — в UTF-8. Нулевой байт
 * разделяет IP и {@code User-Agent}, чтобы разные пары не склеились в одну строку. Без соли дня отпечаток не
 * сопоставить с человеком, а соль стирается после дня — восстановить, кто был, тогда уже нельзя.
 */
final class Fingerprints {

    static final int SIZE = 32;

    private Fingerprints() {}

    static byte[] of(byte[] salt, Visitor visitor) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен в каждой реализации Java.
            throw new InternalException("SHA-256 is not available", e);
        }
        digest.update(salt);
        digest.update(visitor.ip().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(visitor.userAgent().getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
