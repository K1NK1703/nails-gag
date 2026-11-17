package gag.nails.notifications.internal;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.hibernate.JDBCException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Сбои фоновой работы модуля в логе (спека §6). Текст ошибок базы может содержать значения строк — почту, имена (так
 * PostgreSQL описывает нарушение ограничения), — поэтому о них пишется только класс исключения и SQLState; полный
 * стек — только у прочих ошибок (ошибок кода), где он нужен для разбора. Ошибка базы ищется по всей цепочке причин и
 * подавленных исключений: обёртка вокруг неё — тоже ошибка базы.
 */
public final class Failures {

    private Failures() {}

    /** В цепочке есть ошибка базы или транзакции: в лог — только {@link #describe}, без текста и стека. */
    public static boolean isDatabase(Throwable failure) {
        return database(failure) != null;
    }

    /**
     * Класс исключения, а если в цепочке есть ошибка базы — ещё и её класс и SQLState: «обёртка caused by класс
     * (SQLState …)». Текстов исключений нет.
     */
    public static String describe(Throwable failure) {
        var found = database(failure);
        var text = new StringBuilder(failure.getClass().getName());
        if (found != null && found != failure) {
            text.append(" caused by ").append(found.getClass().getName());
        }
        var sqlState = sqlState(failure);
        if (sqlState != null) {
            text.append(" (SQLState ").append(sqlState).append(')');
        }
        return text.toString();
    }

    private static @Nullable Throwable database(Throwable failure) {
        for (var throwable : chain(failure)) {
            if (throwable instanceof DataAccessException
                    || throwable instanceof TransactionException
                    || throwable instanceof SQLException
                    || throwable instanceof JDBCException) {
                return throwable;
            }
        }
        return null;
    }

    private static @Nullable String sqlState(Throwable failure) {
        for (var throwable : chain(failure)) {
            var sqlState = switch (throwable) {
                case SQLException sql -> sql.getSQLState();
                case JDBCException jdbc -> jdbc.getSQLState();
                default -> null;
            };
            if (sqlState != null) {
                return sqlState;
            }
        }
        return null;
    }

    /** Исключение, его причины и подавленные — в ширину, каждое по разу (цепочки бывают с циклами). */
    private static Iterable<Throwable> chain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var order = new ArrayList<Throwable>();
        var queue = new ArrayDeque<Throwable>();
        queue.add(failure);
        while (!queue.isEmpty()) {
            var throwable = queue.poll();
            if (!seen.add(throwable)) {
                continue;
            }
            order.add(throwable);
            var cause = throwable.getCause();
            if (cause != null) {
                queue.add(cause);
            }
            Collections.addAll(queue, throwable.getSuppressed());
        }
        return order;
    }
}
