package gag.nails.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Ошибка базы в логе — класс и SQLState, без текста: в нём бывают значения строк. */
class FailuresTest {

    @Test
    void databaseFailureIsDescribedByClassAndSqlState() {
        var failure = new DataIntegrityViolationException(
                "Key (email)=(anna@example.com) already exists",
                new SQLException("Key (email)=(anna@example.com) already exists", "23505"));

        assertThat(Failures.isDatabase(failure)).isTrue();
        assertThat(Failures.describe(failure))
                .isEqualTo("org.springframework.dao.DataIntegrityViolationException (SQLState 23505)")
                .doesNotContain("anna@example.com");
    }

    @Test
    void codeFailureIsNotDatabase() {
        var failure = new InternalException("bug");

        assertThat(Failures.isDatabase(failure)).isFalse();
        assertThat(Failures.describe(failure)).isEqualTo("gag.nails.common.error.InternalException");
    }
}
