package gag.nails.analytics;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * С включёнными задачами счётчика уборка запускается один раз сразу после старта приложения. Ночной запуск по
 * расписанию выключен ({@code purge-cron=-}), а контекст с планировщиком не остаётся в кеше для других тестов: у него
 * своя база, и уборка на старте (по часам тестов, 2026-10-01) не трогает строк других тестов.
 */
@AnalyticsModuleTest
@TestPropertySource(properties = {"nails-gag.analytics.jobs.enabled=true", "nails-gag.analytics.jobs.purge-cron=-"})
@DirtiesContext
class AnalyticsJobsStartupTest {

    @MockitoSpyBean
    AnalyticsMaintenance maintenance;

    @Test
    void jobRunsOnceAfterStart() {
        verify(maintenance).purgeExpired();
    }
}
