package gag.nails.booking.internal.marks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.internal.booking.BookingEvents;
import gag.nails.booking.internal.booking.BookingFinder;
import gag.nails.booking.internal.booking.CurrentRules;
import gag.nails.booking.model.MarkResult;
import gag.nails.catalog.CatalogQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ErrorCodes;
import gag.nails.common.error.InternalException;
import gag.nails.common.validation.CommandValidator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Как пакетная отметка разбирает сбои записи, без базы: транзакция каждой записи подменена и бросает заданное
 * исключение. Ожидаемый отказ — код записи, блокировка — {@code common.busy}, прочее — {@code common.internal}, а
 * отказ инфраструктуры прерывает пакет. Остальные зависимости до транзакции записи не доходят: их заглушки падают
 * при любом обращении.
 */
class BatchMarkFailuresTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final List<UUID> both = List.of(first, second);

    @Test
    void expectedFailureKeepsItsCode() {
        var results = marks(id -> new BookingNotFoundException(id)).completeAsPlanned(both, Actor.master());

        assertThat(results)
                .containsExactly(
                        new MarkResult(first, "booking.booking_not_found"),
                        new MarkResult(second, "booking.booking_not_found"));
    }

    @Test
    void lockFailureIsBusy() {
        assertThat(marks(id -> new CannotAcquireLockException("lock timeout")).completeAsPlanned(both, Actor.master()))
                .containsExactly(new MarkResult(first, ErrorCodes.BUSY), new MarkResult(second, ErrorCodes.BUSY));
        assertThat(marks(id -> new PessimisticLockingFailureException("deadlock"))
                        .completeAsPlanned(both, Actor.master()))
                .containsExactly(new MarkResult(first, ErrorCodes.BUSY), new MarkResult(second, ErrorCodes.BUSY));
    }

    @Test
    void unexpectedFailureIsInternal() {
        assertThat(marks(id -> new InternalException("unexpected")).completeAsPlanned(both, Actor.master()))
                .extracting(MarkResult::errorCode)
                .containsExactly(ErrorCodes.INTERNAL, ErrorCodes.INTERNAL);
        // Сбой фиксации (например, ограничение базы на flush) — сбой этой записи, а не всей базы.
        assertThat(marks(id -> new TransactionSystemException("commit failed")).completeAsPlanned(both, Actor.master()))
                .extracting(MarkResult::errorCode)
                .containsExactly(ErrorCodes.INTERNAL, ErrorCodes.INTERNAL);
    }

    @Test
    void infrastructureFailureAbortsBatch() {
        List<Function<UUID, RuntimeException>> failures = List.of(
                id -> new DataAccessResourceFailureException("database is down"),
                id -> new TransientDataAccessResourceException("database is restarting"),
                id -> new CannotCreateTransactionException("no connection"));
        for (var failure : failures) {
            var transactions = new FailingTransactions(both, failure);
            var expected = failure.apply(first).getClass();

            assertThatThrownBy(() -> marks(transactions).completeAsPlanned(both, Actor.master()))
                    .isInstanceOf(expected);
            assertThat(transactions.attempts).as(expected.getSimpleName()).isEqualTo(1);
        }
    }

    private DefaultVisitMarks marks(Function<UUID, RuntimeException> failure) {
        return marks(new FailingTransactions(both, failure));
    }

    private static DefaultVisitMarks marks(FailingTransactions transactions) {
        return new DefaultVisitMarks(
                untouched(BookingFinder.class),
                untouched(CurrentRules.class),
                untouched(BookingEvents.class),
                untouched(ClientService.class),
                untouched(ClientQueries.class),
                untouched(CatalogQueries.class),
                untouched(CommandValidator.class),
                transactions);
    }

    /** Заглушка, которая падает при любом обращении: до транзакции записи пакет её трогать не должен. */
    private static <T> T untouched(Class<T> type) {
        return mock(type, invocation -> {
            throw new AssertionError(type.getSimpleName() + "."
                    + invocation.getMethod().getName() + " не должен вызываться до транзакции записи");
        });
    }

    /** Транзакция записи, которая сразу падает; какой записи — по порядку вызовов. */
    private static final class FailingTransactions extends TransactionTemplate {

        private final List<UUID> ids;
        private final Function<UUID, RuntimeException> failure;
        private int attempts;

        FailingTransactions(List<UUID> ids, Function<UUID, RuntimeException> failure) {
            this.ids = ids;
            this.failure = failure;
        }

        @Override
        public <T> @Nullable T execute(TransactionCallback<T> action) throws TransactionException {
            throw failure.apply(ids.get(attempts++));
        }
    }
}
