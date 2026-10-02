package gag.nails.common.persistence;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Имя ограничения БД, нарушенного при записи. Модуль переводит его в своё видимое исключение на
 * границе публичного API (спецификация, раздел 9): имя ограничения задано в миграции и стабильно,
 * текст сообщения базы — нет. Имя берётся у Hibernate, а если он его не извлёк (не все классы
 * ошибок PostgreSQL он разбирает) — из сообщения драйвера. Ограничение срабатывает при flush, поэтому
 * код, который на него полагается, делает {@code saveAndFlush()} или {@code flush()} сам и ловит
 * исключение там.
 */
public final class ConstraintViolations {

    /** PostgreSQL: {@code ... violates unique constraint "client_phone_key"}, {@code ... violates exclusion constraint "slot_no_overlap"}. */
    private static final Pattern CONSTRAINT_IN_MESSAGE = Pattern.compile("constraint \"([^\"]+)\"");

    private ConstraintViolations() {}

    public static Optional<String> constraintName(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return Optional.of(violation.getConstraintName());
            }
            if (cause instanceof SQLException sql && sql.getMessage() != null) {
                var matcher = CONSTRAINT_IN_MESSAGE.matcher(sql.getMessage());
                if (matcher.find()) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }
}
