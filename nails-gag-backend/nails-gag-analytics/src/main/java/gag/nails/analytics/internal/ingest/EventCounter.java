package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.internal.count.DailyCounts;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.internal.visitor.Visitors;
import gag.nails.analytics.model.Metric;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Прибавляет проверенное событие к счётчикам дня. Только внутри транзакции ({@code MANDATORY}) — её открывает
 * {@link DefaultAnalyticsIngest}: посетитель и все счётчики события фиксируются вместе или не фиксируются совсем.
 *
 * <p>Строки меняются всегда в одном порядке — соль дня, отпечаток, {@code visitors}, счётчик события, источник,
 * устройство, — поэтому одновременные события ждут друг друга, но не взаимоблокируются.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class EventCounter {

    static final Tally VISITORS = new Tally(Metric.VISITORS, "all");

    private final DailyCounts counts;
    private final Visitors visitors;

    EventCounter(DailyCounts counts, Visitors visitors) {
        this.counts = counts;
        this.visitors = visitors;
    }

    void count(LocalDate day, AcceptedEvent event) {
        var visitor = event.visitor();
        if (visitor != null && visitors.firstVisitOfDay(day, visitor)) {
            counts.increment(day, VISITORS);
        }
        for (var tally : event.tallies()) {
            counts.increment(day, tally);
        }
    }
}
