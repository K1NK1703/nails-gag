package gag.nails.analytics.internal.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.analytics.model.Metric;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Счётчики: только внутри транзакции; каждая метрика перечисления проходит ограничение таблицы. Без фиксаций. */
@AnalyticsModuleTest
class DailyCountsTest {

    private static final LocalDate DAY = LocalDate.of(2313, 2, 1);

    @Autowired
    DailyCounts counts;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** {@code CHECK (metric IN …)} в базе и {@link Metric} не разошлись. Транзакция откатывается. */
    @ParameterizedTest
    @EnumSource(Metric.class)
    void everyMetricFitsTheTable(Metric metric) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            counts.increment(DAY, new Tally(metric, "key"));
            assertThat(jdbc.sql("select count from analytics.daily_count where day = :day and metric = :metric")
                            .param("day", DAY)
                            .param("metric", MetricCodes.code(metric))
                            .query(Long.class)
                            .single())
                    .isOne();
            status.setRollbackOnly();
        });
    }

    @Test
    void needsCallerTransaction() {
        assertThatThrownBy(() -> counts.increment(DAY, new Tally(Metric.MAP_OPEN, "map")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
