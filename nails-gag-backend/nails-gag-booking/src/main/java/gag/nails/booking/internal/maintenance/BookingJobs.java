package gag.nails.booking.internal.maintenance;

import gag.nails.booking.BookingMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Запуск фоновых задач: отмена и истечение — раз в минуту (спецификация 7.7), удаление референсов с
 * истёкшим сроком — раз в сутки, по умолчанию в 04:00 по времени салона
 * ({@code nails-gag.booking.jobs.reference-cleanup-cron}, {@code nails-gag.time-zone}). Задачи
 * независимы: сбой одной не отменяет другую, следующий запуск попробует снова. Выключается свойством
 * {@code nails-gag.booking.jobs.enabled=false} — так делают тесты, которые вызывают
 * {@link BookingMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.booking.jobs.enabled", havingValue = "true", matchIfMissing = true)
class BookingJobs {

    static final String DEFAULT_REFERENCE_CLEANUP_CRON = "0 0 4 * * *";

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

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

    @Scheduled(
            cron = "${nails-gag.booking.jobs.reference-cleanup-cron:" + DEFAULT_REFERENCE_CLEANUP_CRON + "}",
            zone = "${nails-gag.time-zone:Europe/Moscow}")
    void removeExpiredReferences() {
        var removed = maintenance.removeExpiredReferences();
        if (removed > 0) {
            log.info("booking maintenance removed {} expired references", removed);
        }
    }
}
