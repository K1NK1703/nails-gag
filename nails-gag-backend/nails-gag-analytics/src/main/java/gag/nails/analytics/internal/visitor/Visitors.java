package gag.nails.analytics.internal.visitor;

import gag.nails.analytics.internal.AnalyticsProperties;
import gag.nails.analytics.internal.count.MetricCodes;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.model.Metric;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Уникальные посетители дня по суточному отпечатку. Только внутри транзакции приёма ({@code MANDATORY}). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class Visitors {

    /** Ключ счётчика уникальных посетителей дня. */
    public static final Tally ALL = new Tally(Metric.VISITORS, "all");

    /**
     * Отпечаток вставляется, только пока посетителей за день меньше предела: число берётся из счётчика
     * {@code visitors/all}, который растёт вместе с каждым новым отпечатком. Проверка без блокировки — одновременные
     * новые посетители могут превысить предел на число таких гонок, это допустимо.
     */
    private static final String INSERT = """
            insert into analytics.visitor (day, hash)
            select :day, :hash
            where coalesce((select count from analytics.daily_count
                            where day = :day and metric = :metric and key = :key), 0) < :max
            on conflict (day, hash) do nothing""";

    private final EntityManager entityManager;
    private final DaySalts salts;
    private final int maxPerDay;

    Visitors(EntityManager entityManager, DaySalts salts, AnalyticsProperties properties) {
        this.entityManager = entityManager;
        this.salts = salts;
        this.maxPerDay = properties.maxVisitorsPerDay();
    }

    /**
     * Отмечает посетителя за день.
     *
     * @return {@code true}, если за этот день он впервые: тот же адрес и {@code User-Agent} в тот же день дают тот же
     *     отпечаток, а в другой день — другой (у дня своя соль). {@code false} и тогда, когда за день уже набран предел
     *     посетителей ({@code nails-gag.analytics.max-visitors-per-day}): новый не отмечается и не считается
     */
    public boolean firstVisitOfDay(LocalDate day, Visitor visitor) {
        var hash = Fingerprints.of(salts.of(day), visitor);
        return entityManager
                        .createNativeQuery(INSERT)
                        .setParameter("day", day)
                        .setParameter("hash", hash)
                        .setParameter("metric", MetricCodes.code(ALL.metric()))
                        .setParameter("key", ALL.key())
                        .setParameter("max", maxPerDay)
                        .executeUpdate()
                == 1;
    }
}
