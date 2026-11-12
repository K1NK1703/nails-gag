package gag.nails.analytics.internal.report;

import gag.nails.analytics.internal.count.MetricCodes;
import gag.nails.analytics.model.Metric;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Суммы дневных счётчиков за период: каждый метод — один запрос, суммы считает база. Период — уже проверенный
 * вызывающим, обе границы включительно; отбор идёт по ведущему столбцу первичного ключа {@code (day, metric, key)}.
 * {@code sum(bigint)} в PostgreSQL — {@code numeric}, поэтому суммы приводятся к {@code bigint}: переполнение (на
 * деле недостижимое) — ошибка базы, а не обрезанное число. Только внутри транзакции отчёта ({@code MANDATORY}).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class PeriodCounts {

    /** Сумма ключа за период и сумма всей метрики (окно по метрике поверх группировки). */
    private static final String KEY_SUMS = """
            select metric,
                   key,
                   sum(count)::bigint as key_count,
                   (sum(sum(count)) over (partition by metric))::bigint as metric_total
            from analytics.daily_count
            where day between :from and :to
            group by metric, key""";

    private static final String DAY_SUMS = """
            select day, metric, sum(count)::bigint as day_count
            from analytics.daily_count
            where day between :from and :to and metric in (:metrics)
            group by day, metric""";

    private final EntityManager entityManager;

    PeriodCounts(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Ключи всех метрик, засчитанные в периоде, с суммами; порядок не задан. Метрик без событий нет. */
    List<KeySum> keySums(LocalDate from, LocalDate to) {
        return tuples(entityManager
                        .createNativeQuery(KEY_SUMS, Tuple.class)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getResultList())
                .stream()
                .map(row -> new KeySum(
                        MetricCodes.metric(row.get("metric", String.class)),
                        row.get("key", String.class),
                        row.get("key_count", Long.class),
                        row.get("metric_total", Long.class)))
                .toList();
    }

    /** Суммы метрик {@code metrics} по дням периода; порядок не задан. Дней и метрик без событий нет. */
    List<DaySum> daySums(LocalDate from, LocalDate to, Collection<Metric> metrics) {
        var codes = metrics.stream().map(MetricCodes::code).toList();
        return tuples(entityManager
                        .createNativeQuery(DAY_SUMS, Tuple.class)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .setParameter("metrics", codes)
                        .getResultList())
                .stream()
                .map(row -> new DaySum(
                        row.get("day", LocalDate.class),
                        MetricCodes.metric(row.get("metric", String.class)),
                        row.get("day_count", Long.class)))
                .toList();
    }

    /** Результат запроса с типом {@link Tuple}: JPA отдаёт его как сырой список. */
    @SuppressWarnings("unchecked")
    private static List<Tuple> tuples(List<?> rows) {
        return (List<Tuple>) rows;
    }

    /**
     * Ключ метрики за период.
     *
     * @param count сумма ключа за дни периода
     * @param total сумма всех ключей метрики за период
     */
    record KeySum(Metric metric, String key, long count, long total) {}

    /** Сумма всех ключей метрики за день. */
    record DaySum(LocalDate day, Metric metric, long count) {}
}
