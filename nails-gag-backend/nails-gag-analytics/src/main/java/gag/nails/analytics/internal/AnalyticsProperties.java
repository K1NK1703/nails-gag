package gag.nails.analytics.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.analytics.*}; неверные значения не дают приложению стартовать.
 *
 * @param siteHost домен сайта в нижнем регистре, без порта ({@code nails-gag.ru}; в профиле {@code local} —
 *     {@code localhost}): переход с него (с {@code www.} или без) — не внешний источник, а {@code direct}. Своя
 *     настройка, а не {@code nails-gag.api.site-origin}: модуль зависит только от {@code common}
 * @param maxKeysPerMetric сколько разных ключей у метрики за день, не считая {@code other}; новый ключ сверх предела
 *     засчитывается в {@code other} — защита от раздувания таблицы мусорными значениями
 */
@Validated
@ConfigurationProperties("nails-gag.analytics")
public record AnalyticsProperties(
        @NotNull @Pattern(regexp = HOST) String siteHost,
        @DefaultValue("50") @Positive int maxKeysPerMetric) {

    /** Метка имени хоста: латиница в нижнем регистре, цифры и дефис, без дефиса по краям, до 63 символов. */
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

    /** Имя хоста: метки через точку. */
    public static final String HOST = LABEL + "(?:\\." + LABEL + ")*";
}
