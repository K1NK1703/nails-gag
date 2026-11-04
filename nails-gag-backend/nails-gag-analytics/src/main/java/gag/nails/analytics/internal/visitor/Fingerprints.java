package gag.nails.analytics.internal.visitor;

import gag.nails.common.error.InternalException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Суточный отпечаток посетителя: {@code SHA-256(соль дня ‖ длина адреса ‖ байты адреса ‖ userAgent)}: длина — один
 * байт (4 или 16), адрес — {@link Visitor#address()}, {@code User-Agent} — в UTF-8. Соль фиксированной длины, а длина
 * адреса записана перед ним, поэтому граница между адресом и {@code User-Agent} однозначна и разделитель не нужен:
 * разные пары не дают одну и ту же строку. Без соли дня отпечаток не сопоставить с человеком, а соль стирается после
 * дня — восстановить, кто был, тогда уже нельзя.
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
        digest.update((byte) visitor.address().length);
        digest.update(visitor.address());
        digest.update(visitor.userAgent().getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
