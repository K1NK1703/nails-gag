package gag.nails.notifications.internal;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Сбои фоновой работы модуля в логе (спека §6). Текст ошибок базы может содержать значения строк — почту, имена (так
 * PostgreSQL описывает нарушение ограничения), — поэтому о них пишется только класс исключения и SQLState; полный
 * стек — только у прочих ошибок (ошибок кода), где он нужен для разбора.
 */
public final class Failures {

    private Failures() {}

    /** Ошибка базы или транзакции: в лог — только {@link #describe}, без текста и стека. */
    public static boolean isDatabase(Throwable failure) {
        return failure instanceof DataAccessException || failure instanceof TransactionException;
    }

    /** Класс исключения и SQLState первой ошибки JDBC в цепочке причин, если она есть. */
    public static String describe(Throwable failure) {
        var sqlState = sqlState(failure);
        return failure.getClass().getName() + (sqlState == null ? "" : " (SQLState " + sqlState + ")");
    }

    private static @Nullable String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }
}
