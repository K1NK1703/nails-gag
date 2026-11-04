package gag.nails.analytics.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.analytics.AnalyticsIngest;
import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.analytics.command.RecordEvent;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.model.Metric;
import gag.nails.common.error.InternalException;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.testsupport.MutableClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Пределы транзакции приёма ({@code SET LOCAL}) действуют внутри неё и не утекают в пул: в пуле одно соединение, так
 * что запрос после приёма заведомо идёт по тому же соединению, что и сама запись. Свой контекст с таким пулом после
 * класса закрывается и не остаётся в кеше для других тестов. День — 2313-04-01, убирается за собой.
 */
@AnalyticsModuleTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
@DirtiesContext
class TransactionLimitsTest {

    private static final LocalDate DAY = LocalDate.of(2313, 4, 1);
    private static final List<String> SETTINGS = List.of("lock_timeout", "statement_timeout", "synchronous_commit");
    private static final RecordEvent MAP = new RecordEvent("map_open", null, null, null, null, null, null, null);
    private static final AcceptedEvent ACCEPTED_MAP =
            new AcceptedEvent(List.of(new Tally(Metric.MAP_OPEN, "map")), null);
    private static final String IP = "203.0.113.7";
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/132.0";

    @Autowired
    AnalyticsIngest ingest;

    @Autowired
    EventCounter counter;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MutableClock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    @BeforeEach
    void onOwnDay() {
        clock.set(salonTimeZone.at(DAY, LocalTime.NOON));
    }

    @AfterEach
    void removeOwnDay() {
        jdbc.sql("delete from analytics.daily_count where day = :day")
                .param("day", DAY)
                .update();
    }

    /** Транзакция откатывается — данных не остаётся. */
    @Test
    void limitsHoldInsideIngestTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            counter.count(DAY, ACCEPTED_MAP);
            assertThat(settings())
                    .containsEntry("lock_timeout", "500ms")
                    .containsEntry("statement_timeout", "2s")
                    .containsEntry("synchronous_commit", "off");
            status.setRollbackOnly();
        });
    }

    @Test
    void limitsDoNotLeakIntoThePool() {
        var backend = backend();
        var defaults = settings();
        assertThat(defaults).doesNotContainEntry("statement_timeout", "2s");

        ingest.record(MAP, IP, BROWSER);
        assertThat(mapCount()).isOne();
        assertThat(backend()).isEqualTo(backend);
        assertThat(settings()).isEqualTo(defaults);

        // После отката (сбой записи на переполнении счётчика) — тоже.
        jdbc.sql("update analytics.daily_count set count = :count where day = :day")
                .param("count", Long.MAX_VALUE)
                .param("day", DAY)
                .update();
        ingest.record(MAP, IP, BROWSER);
        assertThat(mapCount()).isEqualTo(Long.MAX_VALUE);
        assertThat(backend()).isEqualTo(backend);
        assertThat(settings()).isEqualTo(defaults);
    }

    /** Уровень изоляции не {@code READ COMMITTED} — сбой приёма, а не тихая запись с другой видимостью соли. */
    @Test
    void otherIsolationIsRefused() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> counter.count(DAY, ACCEPTED_MAP)))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("repeatable read");
        assertThat(mapCount()).isZero();
    }

    private Map<String, String> settings() {
        return SETTINGS.stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> jdbc.sql("select current_setting(:name)")
                                .param("name", name)
                                .query(String.class)
                                .single()));
    }

    private int backend() {
        return jdbc.sql("select pg_backend_pid()").query(Integer.class).single();
    }

    private long mapCount() {
        return jdbc.sql("select coalesce(max(count), 0) from analytics.daily_count where day = :day")
                .param("day", DAY)
                .query(Long.class)
                .single();
    }
}
