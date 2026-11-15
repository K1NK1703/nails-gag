package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.internal.count.DailyCounts;
import gag.nails.analytics.internal.visitor.Visitors;
import gag.nails.common.error.InternalException;
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
     *
     * <p>Тем же запросом читается уровень изоляции: на {@code READ COMMITTED} держится общая соль дня при
     * одновременных первых событиях ({@code DaySalts} читает соль, вставленную чужой транзакцией после начала своей).
     * Уровень не задаётся у транзакции явно — тогда драйвер на каждое событие спрашивал бы его у базы лишним
     * запросом; другой уровень (смена умолчания базы) — сбой приёма, он уходит в лог.
     */
    private static final String LIMITS = """
            select set_config('lock_timeout', '500ms', true),
                   set_config('statement_timeout', '2s', true),
                   set_config('synchronous_commit', 'off', true),
                   current_setting('transaction_isolation')""";

    private static final String READ_COMMITTED = "read committed";
    private static final int ISOLATION_COLUMN = 3;

    private final EntityManager entityManager;
    private final DailyCounts counts;
    private final Visitors visitors;

    EventCounter(EntityManager entityManager, DailyCounts counts, Visitors visitors) {
        this.entityManager = entityManager;
        this.counts = counts;
        this.visitors = visitors;
    }

    /**
     * Первым запросом транзакции ставит её пределы и сверяет уровень изоляции, затем отмечает посетителя и прибавляет
     * счётчики.
     *
     * @throws InternalException если транзакция не {@code READ COMMITTED}
     */
    void count(LocalDate day, AcceptedEvent event) {
        var settings = (Object[]) entityManager.createNativeQuery(LIMITS).getSingleResult();
        if (!READ_COMMITTED.equals(settings[ISOLATION_COLUMN])) {
            throw new InternalException(
                    "analytics ingest needs read committed, the transaction is " + settings[ISOLATION_COLUMN]);
        }
        var visitor = event.visitor();
        if (visitor != null && visitors.firstVisitOfDay(day, visitor)) {
            counts.increment(day, Visitors.ALL);
        }
        for (var tally : event.tallies()) {
            counts.increment(day, tally);
        }
    }
}
