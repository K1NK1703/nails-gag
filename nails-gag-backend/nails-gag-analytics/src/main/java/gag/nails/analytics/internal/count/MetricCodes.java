package gag.nails.analytics.internal.count;

import gag.nails.analytics.model.Metric;
import java.util.Locale;

/** Имя метрики в базе — имя Java в нижнем регистре ({@code PAGE_VIEW} → {@code page_view}). */
public final class MetricCodes {

    private MetricCodes() {}

    public static String code(Metric metric) {
        return metric.name().toLowerCase(Locale.ROOT);
    }
}
