package gag.nails.identity.internal.maintenance;

import gag.nails.identity.IdentityMaintenance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Обслуживание входа раз в сутки, в 04:30 по времени салона ({@code nails-gag.time-zone}), когда
 * записей на сайте нет. Выключается свойством {@code nails-gag.identity.jobs.enabled=false} — так
 * делают тесты, которые вызывают {@link IdentityMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.identity.jobs.enabled", havingValue = "true", matchIfMissing = true)
class IdentityJobs {

    private final IdentityMaintenance maintenance;

    IdentityJobs(IdentityMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "${nails-gag.time-zone:Europe/Moscow}")
    void cleanUp() {
        maintenance.cleanUp();
    }
}
