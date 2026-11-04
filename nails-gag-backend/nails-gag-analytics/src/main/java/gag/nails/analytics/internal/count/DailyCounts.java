package gag.nails.analytics.internal.count;

import gag.nails.analytics.internal.AnalyticsProperties;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Дневные счётчики. Только внутри транзакции приёма ({@code MANDATORY}): все прибавления события фиксируются вместе.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class DailyCounts {

    /**
     * Одна вставка: новый ключ сверх предела заменяется на {@code other} прямо в запросе, существующий ключ
     * прибавляется всегда. Сам {@code other} в предел не упирается: сверх предела ключ и так становится {@code other},
     * а число ключей считается без него — {@code other}, появившийся раньше (неизвестный путь страницы), не отнимает
     * места у обычных ключей.
     *
     * <p>Проверка предела без блокировки: два одновременных новых ключа у метрики на пределе могут оба пройти — предел
     * превышается на число таких гонок, это допустимо (защита от раздувания, а не точная граница), поэтому и
     * ограничения в базе нет. Подзапросы идут по первичному ключу {@code (day, metric, key)}.
     */
    private static final String INCREMENT = """
            insert into analytics.daily_count as c (day, metric, key, count)
            select :day, :metric,
                   case when exists (select 1 from analytics.daily_count
                                     where day = :day and metric = :metric and key = :key)
                          or (select count(*) from analytics.daily_count
                              where day = :day and metric = :metric and key <> :other) < :maxKeys
                        then :key
                        else :other
                   end,
                   1
            on conflict (day, metric, key) do update set count = c.count + 1""";

    private final EntityManager entityManager;
    private final int maxKeys;

    DailyCounts(EntityManager entityManager, AnalyticsProperties properties) {
        this.entityManager = entityManager;
        this.maxKeys = properties.maxKeysPerMetric();
    }

    /** Прибавляет единицу к счётчику дня; новый ключ сверх предела метрики засчитывается в {@link Tally#OTHER}. */
    public void increment(LocalDate day, Tally tally) {
        entityManager
                .createNativeQuery(INCREMENT)
                .setParameter("day", day)
                .setParameter("metric", MetricCodes.code(tally.metric()))
                .setParameter("key", tally.key())
                .setParameter("other", Tally.OTHER)
                .setParameter("maxKeys", maxKeys)
                .executeUpdate();
    }
}
