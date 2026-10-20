package gag.nails.files.internal.storage;

import gag.nails.files.FileMaintenance;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Уборка хранилища раз в сутки, по умолчанию в 03:30 по времени салона ({@code nails-gag.files.jobs.cleanup-cron},
 * {@code nails-gag.time-zone}). Шаги независимы: сбой одного пишется в лог и не мешает другому, следующий запуск
 * попробует снова. Выключается свойством {@code nails-gag.files.jobs.enabled=false} — так делают тесты, которые
 * вызывают {@link FileMaintenance} сами.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "nails-gag.files.jobs.enabled", havingValue = "true", matchIfMissing = true)
class FileJobs {

    static final String DEFAULT_CLEANUP_CRON = "0 30 3 * * *";

    private static final Logger log = LoggerFactory.getLogger(FileJobs.class);

    private final FileMaintenance maintenance;

    FileJobs(FileMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(
            cron = "${nails-gag.files.jobs.cleanup-cron:" + DEFAULT_CLEANUP_CRON + "}",
            zone = "${nails-gag.time-zone:Europe/Moscow}")
    void cleanUp() {
        step("orphan files", maintenance::removeOrphans);
        step("stray directories and upload leftovers", maintenance::removeStrayDirectories);
    }

    private static void step(String what, IntSupplier step) {
        try {
            var removed = step.getAsInt();
            if (removed > 0) {
                log.info("file cleanup removed {} {}", removed, what);
            }
        } catch (RuntimeException e) {
            log.error("file cleanup failed to remove {}", what, e);
        }
    }
}
