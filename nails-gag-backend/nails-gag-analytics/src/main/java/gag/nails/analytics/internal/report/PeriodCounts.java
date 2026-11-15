package gag.nails.analytics.internal.report;

import gag.nails.analytics.internal.count.MetricCodes;
import gag.nails.analytics.model.Metric;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Суммы дневных счётчиков за период: каждый метод — один запрос, суммы считает база. Период — уже проверенный
 * вызывающим, обе границы включительно; отбор идёт по ведущему столбцу первичного ключа {@code (day, metric, key)}.
 * {@code sum(bigint)} в PostgreSQL — {@code numeric}, поэтому суммы приводятся к {@code bigint}: переполнение (на
 * деле недостижимое) — ошибка базы, а не обрезанное число. Метрики запроса заданы явно: строка с кодом, которого
 * вызывающий не ждёт, в результат не попадает. Только внутри транзакции отчёта ({@code MANDATORY}).
 *
 * <p>У каждого запроса предел времени {@value #QUERY_TIMEOUT_MILLIS} мс (по истечении драйвер отменяет запрос, вызов
 * бросает ошибку базы): затянувшийся отчёт держит блокировку чтения таблицы, за ней в очередь встаёт
 * {@code ALTER TABLE} выкладки, а за ним — запись приёма.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class PeriodCounts {

    /** Предел времени каждого запроса, мс. */
    static final int QUERY_TIMEOUT_MILLIS = 10_000;

    private static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    /**
     * Сумма ключа за период и сумма всей метрики — окном по всем её ключам до отсечения; из ключей метрики остаются
     * {@code :limit} самых частых. Порядок отсечения — по убыванию суммы, при равенстве — по ключу в порядке байтов
     * ({@code collate "C"}): от правил сравнения строк базы он не зависит и для ключей метрик (ASCII) совпадает с
     * {@link String#compareTo}, которым вызывающий упорядочивает оставшиеся ключи.
     */
    private static final String KEY_SUMS = """
            select metric, key, key_count, metric_total
            from (select metric,
                         key,
                         sum(count)::bigint as key_count,
                         (sum(sum(count)) over (partition by metric))::bigint as metric_total,
                         row_number() over (partition by metric order by sum(count) desc, key collate "C") as place
                  from analytics.daily_count
                  where day between :from and :to and metric in (:metrics)
                  group by metric, key) sums
            where place <= :limit""";

    private static final String DAY_SUMS = """
            select day, metric, sum(count)::bigint as day_count
            from analytics.daily_count
            where day between :from and :to and metric in (:metrics)
            group by day, metric""";

    private final EntityManager entityManager;
    private final int queryTimeoutMillis;

    @Autowired
    PeriodCounts(EntityManager entityManager) {
        this(entityManager, Duration.ofMillis(QUERY_TIMEOUT_MILLIS));
    }

    /**
     * Свой предел времени запросов — для теста, которому не нужно ждать десять секунд. Драйвер отсчитывает его в целых
     * секундах.
     */
    PeriodCounts(EntityManager entityManager, Duration queryTimeout) {
        this.entityManager = entityManager;
        this.queryTimeoutMillis = Math.toIntExact(queryTimeout.toMillis());
    }

    /**
     * Не больше {@code limit} самых частых ключей каждой из метрик {@code metrics}, засчитанных в периоде, с суммами
     * ключа и всей метрики; порядок не задан. Метрик без событий нет.
     */
    List<KeySum> keySums(LocalDate from, LocalDate to, Collection<Metric> metrics, int limit) {
        return tuples(entityManager
                        .createNativeQuery(KEY_SUMS, Tuple.class)
                        .setHint(QUERY_TIMEOUT_HINT, queryTimeoutMillis)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .setParameter("metrics", codes(metrics))
                        .setParameter("limit", limit)
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
        return tuples(entityManager
                        .createNativeQuery(DAY_SUMS, Tuple.class)
                        .setHint(QUERY_TIMEOUT_HINT, queryTimeoutMillis)
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .setParameter("metrics", codes(metrics))
                        .getResultList())
                .stream()
                .map(row -> new DaySum(
                        row.get("day", LocalDate.class),
                        MetricCodes.metric(row.get("metric", String.class)),
                        row.get("day_count", Long.class)))
                .toList();
    }

    private static List<String> codes(Collection<Metric> metrics) {
        return metrics.stream().map(MetricCodes::code).toList();
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
     * @param total сумма всех ключей метрики за период, включая не попавшие в результат
     */
    record KeySum(Metric metric, String key, long count, long total) {}

    /** Сумма всех ключей метрики за день. */
    record DaySum(LocalDate day, Metric metric, long count) {}
}
