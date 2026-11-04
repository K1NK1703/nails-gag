package gag.nails.finance.internal.recurring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.finance.*}.
 *
 * @param jobs фоновые задачи; в тестах выключены
 */
@Validated
@ConfigurationProperties("nails-gag.finance")
record FinanceProperties(@DefaultValue Jobs jobs) {

    /**
     * Читаются аннотациями {@link FinanceJobs} ({@code @ConditionalOnProperty}, {@code @Scheduled}); поля здесь — чтобы
     * все настройки модуля были описаны в одном месте.
     *
     * @param enabled включены ли фоновые задачи
     * @param recurringCron когда создаются записи повторяющихся шаблонов, по времени салона
     *     ({@code nails-gag.time-zone})
     */
    record Jobs(
            @DefaultValue("true") boolean enabled,

            @DefaultValue(FinanceJobs.DEFAULT_RECURRING_CRON)
            String recurringCron) {}
}
