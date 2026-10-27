package gag.nails.booking.internal.maintenance;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.booking.*}; неверные значения не дают приложению стартовать. Правила записи (лимиты, окна, сроки
 * оферты) — не здесь, а в настройках модуля в базе: их меняет мастер.
 *
 * @param referenceRetentionDays сколько дней хранятся фото-референсы после визита или отмены записи (политика
 *     конфиденциальности обещает 30)
 * @param jobs фоновые задачи; в тестах выключены
 */
@Validated
@ConfigurationProperties("nails-gag.booking")
record BookingProperties(
        @DefaultValue("30") @Positive int referenceRetentionDays,
        @DefaultValue Jobs jobs) {

    /**
     * Читаются аннотациями {@link BookingJobs} ({@code @ConditionalOnProperty}, {@code @Scheduled}); поля здесь — чтобы
     * все настройки модуля были описаны в одном месте.
     *
     * @param enabled включены ли фоновые задачи
     * @param referenceCleanupCron когда удаляются референсы с истёкшим сроком, по времени салона
     *     ({@code nails-gag.time-zone})
     */
    record Jobs(
            @DefaultValue("true") boolean enabled,

            @DefaultValue(BookingJobs.DEFAULT_REFERENCE_CLEANUP_CRON)
            String referenceCleanupCron) {}
}
