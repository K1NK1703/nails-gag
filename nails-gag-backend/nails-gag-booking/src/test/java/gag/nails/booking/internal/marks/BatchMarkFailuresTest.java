package gag.nails.booking.internal.marks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.model.MarkResult;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ErrorCodes;
import gag.nails.common.error.InternalException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Как пакетная отметка разбирает сбои записи, без базы: транзакция каждой записи подменена и бросает заданное
 * исключение. Ожидаемый отказ — код записи, блокировка — {@code common.busy}, прочее — {@code common.internal}, а
 * отказ инфраструктуры прерывает пакет.
 */
class BatchMarkFailuresTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void expectedFailureKeepsItsCode() {
        var results = marks(id -> new BookingNotFoundException(id)).completeAsPlanned(List.of(first), Actor.master());

        assertThat(results).containsExactly(new MarkResult(first, "booking.booking_not_found"));
    }

    @Test
    void lockFailureIsBusy() {
        var results = marks(id -> new CannotAcquireLockException("lock timeout"))
                .completeAsPlanned(List.of(first, second), Actor.master());

        assertThat(results)
                .containsExactly(new MarkResult(first, ErrorCodes.BUSY), new MarkResult(second, ErrorCodes.BUSY));
    }

    @Test
    void unexpectedFailureIsInternal() {
        var results =
                marks(id -> new InternalException("unexpected")).completeAsPlanned(List.of(first), Actor.master());

        assertThat(results).containsExactly(new MarkResult(first, ErrorCodes.INTERNAL));
    }

    @Test
    void infrastructureFailureAbortsBatch() {
        var unavailable = new FailingTransactions(id -> new DataAccessResourceFailureException("database is down"));
        assertThatThrownBy(() -> marks(unavailable).completeAsPlanned(List.of(first, second), Actor.master()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(unavailable.attempts).isEqualTo(1);

        var noTransaction = new FailingTransactions(id -> new CannotCreateTransactionException("no connection"));
        assertThatThrownBy(() -> marks(noTransaction).completeAsPlanned(List.of(first, second), Actor.master()))
                .isInstanceOf(CannotCreateTransactionException.class);
        assertThat(noTransaction.attempts).isEqualTo(1);
    }

    private DefaultVisitMarks marks(Function<UUID, RuntimeException> failure) {
        return marks(new FailingTransactions(failure));
    }

    /** Остальные зависимости до транзакции записи не доходят: она падает раньше. */
    private DefaultVisitMarks marks(FailingTransactions transactions) {
        transactions.ids = List.of(first, second);
        return new DefaultVisitMarks(null, null, null, null, null, null, null, transactions);
    }

    /** Транзакция записи, которая сразу падает; какой записи — по порядку вызовов. */
    private static final class FailingTransactions extends TransactionTemplate {

        private final Function<UUID, RuntimeException> failure;
        private List<UUID> ids = List.of();
        private int attempts;

        FailingTransactions(Function<UUID, RuntimeException> failure) {
            this.failure = failure;
        }

        @Override
        public <T> @Nullable T execute(TransactionCallback<T> action) throws TransactionException {
            throw failure.apply(ids.get(attempts++));
        }
    }
}
