package gag.nails.analytics.internal.count;

import gag.nails.analytics.model.Metric;

/**
 * Счётчик дня, к которому прибавляется событие: метрика и её ключ.
 *
 * @param key ключ метрики, 1–64 символа
 */
public record Tally(Metric metric, String key) {

    /** Ключ, куда засчитывается всё сверх предела ключей метрики за день, и неизвестный путь страницы. */
    public static final String OTHER = "other";
}
