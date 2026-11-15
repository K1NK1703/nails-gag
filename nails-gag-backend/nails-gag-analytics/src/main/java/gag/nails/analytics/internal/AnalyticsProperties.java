package gag.nails.analytics.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.analytics.*}; неверные значения не дают приложению стартовать. Значения по умолчанию — только
 * здесь, в константах {@code DEFAULT_*}.
 *
 * @param siteHost домен сайта в нижнем регистре, без порта ({@code nails-gag.ru}; в профиле {@code local} —
 *     {@code localhost}): переход с него (с {@code www.} или без) — не внешний источник, а {@code direct}. Своя
 *     настройка, а не {@code nails-gag.api.site-origin}: модуль зависит только от {@code common}
 * @param maxKeysPerMetric сколько разных ключей у метрики за день, не считая {@code other}; новый ключ сверх предела
 *     засчитывается в {@code other} — защита от раздувания таблицы мусорными значениями. Предел мягкий: гонка новых
 *     ключей может его немного превысить
 * @param maxVisitorsPerDay сколько суточных отпечатков хранится за день; сверх предела новые посетители не
 *     отмечаются (просмотры считаются как обычно) — защита таблицы отпечатков от накрутки с разных адресов
 * @param maxConcurrentWrites сколько событий пишется в базу одновременно; событие сверх этого числа ждёт места до
 *     50 мс ({@code DefaultAnalyticsIngest.WRITE_WAIT_MILLIS}), затем отбрасывается — приём не занимает общий пул
 *     соединений приложения
 * @param jobs фоновые задачи; в тестах выключены
 */
@Validated
@ConfigurationProperties("nails-gag.analytics")
public record AnalyticsProperties(
        @NotNull @Pattern(regexp = HOST) String siteHost,
        @DefaultValue(DEFAULT_MAX_KEYS_PER_METRIC) @Positive int maxKeysPerMetric,

        @DefaultValue(DEFAULT_MAX_VISITORS_PER_DAY) @Positive
        int maxVisitorsPerDay,

        @DefaultValue(DEFAULT_MAX_CONCURRENT_WRITES) @Positive
        int maxConcurrentWrites,

        @DefaultValue Jobs jobs) {

    public static final String DEFAULT_MAX_KEYS_PER_METRIC = "50";
    public static final String DEFAULT_MAX_VISITORS_PER_DAY = "100000";
    public static final String DEFAULT_MAX_CONCURRENT_WRITES = "3";
    public static final String DEFAULT_JOBS_ENABLED = "true";
    public static final String DEFAULT_PURGE_CRON = "0 30 0 * * *";

    /** Метка имени хоста: латиница в нижнем регистре, цифры и дефис, без дефиса по краям, до 63 символов. */
    public static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

    /** Имя хоста: одна или несколько меток через точку ({@code localhost} — тоже). */
    public static final String HOST = LABEL + "(?:\\." + LABEL + ")*";

    /**
     * Читаются аннотациями {@code AnalyticsJobs} ({@code @ConditionalOnProperty}, {@code @Scheduled}); поля здесь —
     * чтобы все настройки модуля были описаны в одном месте.
     *
     * @param enabled включена ли уборка по расписанию и после старта
     * @param purgeCron когда стираются соли и отпечатки прошедших дней, по времени салона
     *     ({@code nails-gag.time-zone}). Ставить не раньше 00:05: первые пять минут дня уборка не трогает вчерашний
     *     день (запас на опоздавшие события приёма), и запуск раньше оставил бы вчерашние соль и отпечатки ещё на сутки
     */
    public record Jobs(
            @DefaultValue(DEFAULT_JOBS_ENABLED) boolean enabled,

            @DefaultValue(DEFAULT_PURGE_CRON) String purgeCron) {}
}
