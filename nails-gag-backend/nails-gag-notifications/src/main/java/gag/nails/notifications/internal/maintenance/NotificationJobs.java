package gag.nails.notifications.internal.maintenance;

import gag.nails.notifications.NotificationMaintenance;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Фоновые задачи уведомлений:
 *
 * <ul>
 *   <li>отправка ({@link Dispatcher}) — с паузой {@code nails-gag.notifications.jobs.dispatch-delay} (1 минута) от
 *       конца прогона до начала следующего; первый прогон — сразу после старта, догоняя созревшее за время простоя;
 *   <li>уборка ({@link NotificationMaintenance}) — раз в сутки по {@code purge-cron} (04:15 по времени салона) и один
 *       раз после старта ({@link ApplicationReadyEvent});
 *   <li>повтор неудачных публикаций реестра событий — каждые 5 минут, а раз в сутки (вместе с уборкой и после старта)
 *       WARN о публикациях, незавершённых дольше суток ({@link EventPublicationRetries}).
 * </ul>
 *
 * Сбой одного запуска пишется в лог и следующему не мешает. Все задачи вызываются вне транзакции. Выключаются
 * свойством {@code nails-gag.notifications.jobs.enabled=false} — так делают тесты, которые вызывают их сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.notifications.jobs.enabled", havingValue = "true", matchIfMissing = true)
class NotificationJobs {

    /** Пауза между повторами неудачных публикаций, миллисекунды (5 минут); первый — через неё же после старта. */
    static final long RESUBMIT_DELAY_MILLIS = 300_000;

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final Dispatcher dispatcher;
    private final NotificationMaintenance maintenance;
    private final EventPublicationRetries publications;

    NotificationJobs(Dispatcher dispatcher, NotificationMaintenance maintenance, EventPublicationRetries publications) {
        this.dispatcher = dispatcher;
        this.maintenance = maintenance;
        this.publications = publications;
    }

    @Scheduled(
            fixedDelayString = "${nails-gag.notifications.jobs.dispatch-delay:"
                    + NotificationsProperties.DEFAULT_DISPATCH_DELAY + "}")
    void dispatch() {
        try {
            dispatcher.run();
        } catch (RuntimeException e) {
            log.error("notifications dispatch failed, the next run will retry", e);
        }
    }

    /**
     * Запуск уборки и проверки реестра после старта. Ошибка пишется в лог и старт не прерывает: отправка работает и без
     * уборки, её повторит ночной запуск.
     */
    @EventListener(ApplicationReadyEvent.class)
    void purgeExpiredAfterStart() {
        purgeExpired();
    }

    @Scheduled(
            cron = "${nails-gag.notifications.jobs.purge-cron:" + NotificationsProperties.DEFAULT_PURGE_CRON + "}",
            zone = "${nails-gag.time-zone:Europe/Moscow}")
    void purgeExpired() {
        try {
            maintenance.purgeExpired();
        } catch (RuntimeException e) {
            log.error("notifications purge failed, the next run will retry", e);
        }
        try {
            publications.warnAboutStale();
        } catch (RuntimeException e) {
            log.error("event publications check failed, the next run will retry", e);
        }
    }

    @Scheduled(fixedDelay = RESUBMIT_DELAY_MILLIS, initialDelay = RESUBMIT_DELAY_MILLIS)
    void resubmitFailedPublications() {
        try {
            publications.resubmitFailed();
        } catch (RuntimeException e) {
            log.error("event publications resubmission failed, the next run will retry", e);
        }
    }
}
