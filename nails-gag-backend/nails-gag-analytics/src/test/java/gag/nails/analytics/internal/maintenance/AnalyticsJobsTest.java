package gag.nails.analytics.internal.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import gag.nails.common.error.InternalException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Запуск уборки после старта приложения: один раз, сбой не прерывает старт. Что слушатель срабатывает на старте —
 * {@code AnalyticsJobsStartupTest}.
 */
class AnalyticsJobsTest {

    @Test
    void runsPurgeOnceAfterStart() {
        var calls = new AtomicInteger();

        new AnalyticsJobs(() -> {
                    calls.incrementAndGet();
                    return 2;
                })
                .purgeExpiredAfterStart();

        assertThat(calls).hasValue(1);
    }

    @Test
    void failureAfterStartIsLoggedNotThrown() {
        var jobs = new AnalyticsJobs(() -> {
            throw new InternalException("purge failed");
        });

        assertThatCode(jobs::purgeExpiredAfterStart).doesNotThrowAnyException();
    }
}
