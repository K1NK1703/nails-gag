package gag.nails.finance;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * С включёнными задачами финансов обслуживание запускается один раз сразу после старта приложения. Ночной запуск по
 * расписанию в тесте не наступает: он в 00:15 по времени салона.
 */
@FinanceModuleTest
@TestPropertySource(properties = "nails-gag.finance.jobs.enabled=true")
class FinanceJobsStartupTest {

    @MockitoSpyBean
    FinanceMaintenance maintenance;

    @Test
    void maintenanceRunsOnceAfterStart() {
        verify(maintenance).createDueRecurringEntries();
    }
}
