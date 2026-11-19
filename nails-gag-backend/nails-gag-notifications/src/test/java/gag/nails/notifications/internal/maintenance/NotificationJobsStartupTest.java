package gag.nails.notifications.internal.maintenance;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import gag.nails.notifications.NotificationMaintenance;
import gag.nails.notifications.NotificationsModuleTest;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * С включёнными задачами уведомлений сразу после старта приложения запускаются отправка, уборка и проверка реестра
 * публикаций; повтор неудачных публикаций — только через 5 минут. Отправка подменена (прогон по общей базе не нужен),
 * уборка — настоящая; ночной запуск по расписанию — раз в год. Контекст с планировщиком не остаётся в кеше для других
 * тестов.
 */
@NotificationsModuleTest
@TestPropertySource(
        properties = {
            "nails-gag.notifications.jobs.enabled=true",
            "nails-gag.notifications.jobs.purge-cron=0 0 0 1 1 *",
            "nails-gag.notifications.jobs.dispatch-delay=1h"
        })
@DirtiesContext
class NotificationJobsStartupTest {

    @MockitoBean
    Dispatcher dispatcher;

    @MockitoSpyBean
    NotificationMaintenance maintenance;

    @MockitoBean
    EventPublicationRetries publications;

    @Test
    void jobsRunAfterStart() {
        verify(dispatcher, timeout(10_000)).run();
        verify(maintenance).purgeExpired();
        verify(publications).warnAboutStale();
        verify(publications, never()).resubmitFailed();
    }
}
