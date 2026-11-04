package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.internal.count.DailyCounts;
import gag.nails.analytics.internal.visitor.Visitors;
import jakarta.persistence.EntityManager;
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

    /**
     * Пределы транзакции приёма, только на неё ({@code SET LOCAL}). Ожидание блокировки — не дольше 500 мс, запрос —
     * не дольше 2 с: событие, которое не успело, теряется, а не держит соединение общего пула. Фиксация без ожидания
     * записи журнала на диск ({@code synchronous_commit = off}): при падении базы теряются доли секунды счётчиков —
     * для статистики это допустимо, а держать горячую строку (просмотры {@code /} за день) заблокированной на время
     * fsync каждого события — нет. Целостность базы при этом не страдает, теряются только последние фиксации.
     */
    private static final String LIMITS = """
            select set_config('lock_timeout', '500ms', true),
                   set_config('statement_timeout', '2s', true),
                   set_config('synchronous_commit', 'off', true)""";

    private final EntityManager entityManager;
    private final DailyCounts counts;
    private final Visitors visitors;

    EventCounter(EntityManager entityManager, DailyCounts counts, Visitors visitors) {
        this.entityManager = entityManager;
        this.counts = counts;
        this.visitors = visitors;
    }

    /** Первым запросом транзакции ставит её пределы, затем отмечает посетителя и прибавляет счётчики. */
    void count(LocalDate day, AcceptedEvent event) {
        entityManager.createNativeQuery(LIMITS).getSingleResult();
        var visitor = event.visitor();
        if (visitor != null && visitors.firstVisitOfDay(day, visitor)) {
            counts.increment(day, Visitors.ALL);
        }
        for (var tally : event.tallies()) {
            counts.increment(day, tally);
        }
    }
}
