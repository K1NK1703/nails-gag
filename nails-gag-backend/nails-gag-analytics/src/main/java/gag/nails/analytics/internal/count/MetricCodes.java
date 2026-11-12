package gag.nails.analytics.internal.count;

import gag.nails.analytics.model.Metric;
import gag.nails.common.error.InternalException;
import java.util.Locale;

/** Имя метрики в базе — имя Java в нижнем регистре ({@code PAGE_VIEW} → {@code page_view}). */
public final class MetricCodes {

    private MetricCodes() {}

    public static String code(Metric metric) {
        return metric.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Метрика по имени в базе.
     *
     * @throws InternalException если имя не из {@link Metric} — ограничение таблицы такого не пропускает
     */
    public static Metric metric(String code) {
        for (var metric : Metric.values()) {
            if (code(metric).equals(code)) {
                return metric;
            }
        }
        throw new InternalException("unknown analytics metric " + code);
    }
}
