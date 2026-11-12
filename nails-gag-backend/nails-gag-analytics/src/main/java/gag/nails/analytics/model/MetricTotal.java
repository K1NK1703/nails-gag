package gag.nails.analytics.model;

import java.util.List;

/**
 * Метрика за период.
 *
 * @param total сумма всех ключей метрики за период; ноль, если событий не было
 * @param keys ключи метрики, засчитанные в периоде: по убыванию числа, при равенстве — по ключу (порядок
 *     {@link String#compareTo}); пусто, если событий не было
 */
public record MetricTotal(Metric metric, long total, List<KeyCount> keys) {

    public MetricTotal {
        keys = List.copyOf(keys);
    }
}
