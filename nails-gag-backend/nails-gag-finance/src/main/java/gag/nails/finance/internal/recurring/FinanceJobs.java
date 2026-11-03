package gag.nails.finance.internal.recurring;

import gag.nails.finance.FinanceMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Запуск фоновой задачи финансов: записи повторяющихся шаблонов — раз в сутки, по умолчанию в 00:15 по времени салона
 * ({@code nails-gag.finance.jobs.recurring-cron}, {@code nails-gag.time-zone}): сразу после смены дня, чтобы
 * сегодняшние записи уже были. Сбой не мешает следующему запуску, пропущенные дни он догонит. Выключается свойством
 * {@code nails-gag.finance.jobs.enabled=false} — так делают тесты, которые вызывают {@link FinanceMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.finance.jobs.enabled", havingValue = "true", matchIfMissing = true)
class FinanceJobs {

    static final String DEFAULT_RECURRING_CRON = "0 15 0 * * *";

    private static final Logger log = LoggerFactory.getLogger(FinanceJobs.class);

    private final FinanceMaintenance maintenance;

    FinanceJobs(FinanceMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(
            cron = "${nails-gag.finance.jobs.recurring-cron:" + DEFAULT_RECURRING_CRON + "}",
            zone = "${nails-gag.time-zone:Europe/Moscow}")
    void createDueRecurringEntries() {
        var created = maintenance.createDueRecurringEntries();
        if (created > 0) {
            log.info("finance maintenance created {} recurring entries", created);
        }
    }
}
