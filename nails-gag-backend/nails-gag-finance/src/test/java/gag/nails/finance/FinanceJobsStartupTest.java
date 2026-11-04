package gag.nails.finance;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * С включёнными задачами финансов обслуживание запускается один раз сразу после старта приложения. Ночной запуск по
 * расписанию выключен ({@code recurring-cron=-}), а контекст с планировщиком не остаётся в кеше для других тестов.
 */
@FinanceModuleTest
@TestPropertySource(properties = {"nails-gag.finance.jobs.enabled=true", "nails-gag.finance.jobs.recurring-cron=-"})
@DirtiesContext
class FinanceJobsStartupTest {

    @MockitoSpyBean
    FinanceMaintenance maintenance;

    @Test
    void maintenanceRunsOnceAfterStart() {
        verify(maintenance).createDueRecurringEntries();
    }
}
