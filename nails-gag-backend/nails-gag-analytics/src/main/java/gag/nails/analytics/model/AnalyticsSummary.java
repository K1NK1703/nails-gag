package gag.nails.analytics.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Сводка посещений за период.
 *
 * @param from первый день периода, включительно
 * @param to последний день периода, включительно
 * @param visitors сумма уникальных посетителей по дням периода — не уникальные за период: кто заходил три дня, тот
 *     посчитан трижды (отпечаток живёт сутки, сопоставить посетителя разных дней нельзя)
 * @param metrics все метрики, кроме {@link Metric#VISITORS} (она — в {@code visitors}), в порядке {@link Metric},
 *     всегда все: метрика без событий за период — с нулём и без ключей
 */
public record AnalyticsSummary(LocalDate from, LocalDate to, long visitors, List<MetricTotal> metrics) {

    public AnalyticsSummary {
        metrics = List.copyOf(metrics);
    }
}
