package gag.nails.analytics.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import gag.nails.analytics.AnalyticsIngest;
import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.analytics.command.RecordEvent;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.model.Metric;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Места записи и транзакция приёма. Тест мест фиксирует счётчик своего дня (2313-01-01) и убирает его за собой.
 */
@AnalyticsModuleTest
class IngestWritesTest {

    private static final LocalDate DAY = LocalDate.of(2313, 1, 1);
    private static final String IP = "203.0.113.7";
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/132.0";
    private static final RecordEvent MAP = new RecordEvent("map_open", null, null, null, null, null, null, null);

    @Autowired
    AnalyticsIngest ingest;

    @Autowired
    EventCounter counter;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    @BeforeEach
    void onOwnDay() {
        assertThat(mapCount()).isZero();
        clock.set(salonTimeZone.at(DAY, LocalTime.NOON));
    }

    @AfterEach
    void removeOwnDay() {
        for (var table : List.of("daily_count", "visitor", "day_salt")) {
            jdbc.sql("delete from analytics." + table + " where day = :day")
                    .param("day", DAY)
                    .update();
        }
    }

    /** Все места записи заняты — событие отбрасывается сразу, без ожидания и без исключения. */
    @Test
    void eventIsDroppedWhenAllWritesAreBusy() {
        var writes = ((DefaultAnalyticsIngest) ingest).writes;
        var taken = writes.drainPermits();
        try {
            assertThat(taken).isPositive();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> ingest.record(MAP, IP, BROWSER));
            assertThat(mapCount()).isZero();
        } finally {
            writes.release(taken);
        }

        ingest.record(MAP, IP, BROWSER);
        assertThat(mapCount()).isOne();
        assertThat(writes.availablePermits()).isEqualTo(taken);
    }

    @Test
    void counterNeedsCallerTransaction() {
        var event = new AcceptedEvent(List.of(new Tally(Metric.MAP_OPEN, "map")), null);
        assertThatThrownBy(() -> counter.count(DAY, event)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private long mapCount() {
        return jdbc.sql("select coalesce(sum(count), 0) from analytics.daily_count where day = :day")
                .param("day", DAY)
                .query(Long.class)
                .single();
    }
}
