package gag.nails.analytics.internal.maintenance;

import gag.nails.analytics.AnalyticsMaintenance;
import gag.nails.analytics.internal.AnalyticsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Запуск уборки счётчика: соли и отпечатки прошедших дней — раз в сутки, по умолчанию в 00:30 по времени салона
 * ({@code nails-gag.analytics.jobs.purge-cron}, {@code nails-gag.time-zone}): вскоре после смены дня, когда
 * опоздавших событий вчерашнего дня уже нет. Ещё один запуск — сразу после старта приложения
 * ({@link ApplicationReadyEvent}): дни, пока приложение не работало (в том числе ночной запуск, пропущенный из-за
 * перезапуска), убираются, не дожидаясь ночи. Сбой не мешает следующему запуску — он уберёт и пропущенное.
 * Выключается свойством {@code nails-gag.analytics.jobs.enabled=false} — так делают тесты, которые вызывают
 * {@link AnalyticsMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.analytics.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AnalyticsJobs {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsJobs.class);

    private final AnalyticsMaintenance maintenance;

    AnalyticsJobs(AnalyticsMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * Запуск после старта. Ошибка пишется в лог и старт не прерывает: приём и отчёты работают и без уборки, её
     * повторит ночной запуск. Слушатель вызывается вне транзакции, как и требует уборка.
     */
    @EventListener(ApplicationReadyEvent.class)
    void purgeExpiredAfterStart() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.error("analytics purge after start failed, the nightly run will retry", e);
        }
    }

    @Scheduled(
            cron = "${nails-gag.analytics.jobs.purge-cron:" + AnalyticsProperties.DEFAULT_PURGE_CRON + "}",
            zone = "${nails-gag.time-zone:Europe/Moscow}")
    void purgeExpired() {
        maintenance.purgeExpired();
    }
}
