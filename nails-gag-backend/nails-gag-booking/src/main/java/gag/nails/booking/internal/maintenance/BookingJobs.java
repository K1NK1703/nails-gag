package gag.nails.booking.internal.maintenance;

import gag.nails.booking.BookingMaintenance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Запуск фоновых задач раз в минуту (спецификация 7.7). Задачи независимы: сбой одной не отменяет
 * другую в этот же запуск. Выключается свойством
 * {@code nails-gag.booking.jobs.enabled=false} — так делают тесты, которые вызывают
 * {@link BookingMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.booking.jobs.enabled", havingValue = "true", matchIfMissing = true)
class BookingJobs {

    private final BookingMaintenance maintenance;

    BookingJobs(BookingMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void cancelOverduePrepayments() {
        maintenance.cancelOverduePrepayments();
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void expireEarlierTimeOffers() {
        maintenance.expireEarlierTimeOffers();
    }
}
