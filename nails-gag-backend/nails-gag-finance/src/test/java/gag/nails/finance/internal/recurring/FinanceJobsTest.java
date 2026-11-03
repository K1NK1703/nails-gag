package gag.nails.finance.internal.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import gag.nails.common.error.InternalException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Запуск задачи после старта приложения: один раз, сбой не прерывает старт. Что слушатель срабатывает на старте —
 * {@code FinanceJobsStartupTest}.
 */
class FinanceJobsTest {

    @Test
    void runsMaintenanceOnceAfterStart() {
        var calls = new AtomicInteger();

        new FinanceJobs(() -> {
                    calls.incrementAndGet();
                    return 2;
                })
                .createDueRecurringEntriesAfterStart();

        assertThat(calls).hasValue(1);
    }

    @Test
    void failureAfterStartIsLoggedNotThrown() {
        var jobs = new FinanceJobs(() -> {
            throw new InternalException("maintenance failed");
        });

        assertThatCode(jobs::createDueRecurringEntriesAfterStart).doesNotThrowAnyException();
    }
}
