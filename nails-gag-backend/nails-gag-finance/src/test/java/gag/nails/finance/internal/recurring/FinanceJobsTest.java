package gag.nails.finance.internal.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import gag.nails.common.error.InternalException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Запуск задачи после старта приложения: один раз, сбой не прерывает старт. */
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

    @Test
    void listensForApplicationReady() throws NoSuchMethodException {
        var listener = FinanceJobs.class
                .getDeclaredMethod("createDueRecurringEntriesAfterStart")
                .getAnnotation(EventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }
}
