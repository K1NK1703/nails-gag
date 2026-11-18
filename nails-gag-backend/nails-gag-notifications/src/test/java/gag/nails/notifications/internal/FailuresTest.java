package gag.nails.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

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

    /** Сбой соединения и временные ошибки базы пройдут сами; ошибка данных и ошибка кода — нет. */
    @Test
    void transientDatabaseFailuresAreRecognized() {
        assertThat(Failures.isTransientDatabase(new DataAccessResourceFailureException("db down")))
                .isTrue();
        assertThat(Failures.isTransientDatabase(new CannotGetJdbcConnectionException("no connection")))
                .isTrue();
        assertThat(Failures.isTransientDatabase(new CannotCreateTransactionException("no transaction")))
                .isTrue();
        assertThat(Failures.isTransientDatabase(new QueryTimeoutException("timeout")))
                .isTrue();
        assertThat(Failures.isTransientDatabase(
                        new InternalException("wrapped", new SQLException("connection lost", "08006"))))
                .isTrue();
        assertThat(Failures.isTransientDatabase(new SQLException("shutting down", "57P01")))
                .isTrue();

        assertThat(Failures.isTransientDatabase(
                        new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505"))))
                .isFalse();
        assertThat(Failures.isTransientDatabase(new InternalException("bug"))).isFalse();
    }

    @Test
    void codeFailureIsNotDatabase() {
        var failure = new InternalException("bug");

        assertThat(Failures.isDatabase(failure)).isFalse();
        assertThat(Failures.describe(failure)).isEqualTo("gag.nails.common.error.InternalException");
    }

    /** Обёртка вокруг ошибки базы — тоже ошибка базы; SQLState — из найденной, текста нет. */
    @Test
    void databaseFailureIsFoundAnywhereInTheChain() {
        var driver = new SQLException("Key (email)=(anna@example.com) already exists", "23505");
        var wrapped = new InternalException("listener failed: anna@example.com", new IllegalStateException(driver));

        assertThat(Failures.isDatabase(wrapped)).isTrue();
        assertThat(Failures.describe(wrapped))
                .isEqualTo("gag.nails.common.error.InternalException caused by java.sql.SQLException (SQLState 23505)")
                .doesNotContain("anna@example.com");
    }

    @Test
    void databaseFailureIsFoundAmongSuppressed() {
        var failure = new InternalException("dispatch failed");
        failure.addSuppressed(new ConstraintViolationException(
                "duplicate anna@example.com", new SQLException("duplicate", "23505"), "message_dedup_key_key"));

        assertThat(Failures.isDatabase(failure)).isTrue();
        assertThat(Failures.describe(failure))
                .isEqualTo("gag.nails.common.error.InternalException caused by "
                        + "org.hibernate.exception.ConstraintViolationException (SQLState 23505)");
    }

    @Test
    void cyclicChainEnds() {
        var first = new InternalException("first");
        var second = new InternalException("second", first);
        first.addSuppressed(second);

        assertThat(Failures.isDatabase(first)).isFalse();
    }
}
