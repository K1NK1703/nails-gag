package gag.nails.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.InternalException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Уборка солей и отпечатков прошедших дней — на настоящих транзакциях. Уборка стирает всё раньше сегодняшнего дня по
 * часам, поэтому «сегодня» тестов — в 1991 году: дней раньше 2000 года нет ни у других тестов (они берут далёкое
 * будущее), ни у отчётов (период — с 2000-01-01), и уборка трогает только строки этих тестов, а число удалённых
 * отпечатков проверяется точно. Часы сдвигаются через {@link #onDay} и возвращаются перед каждым тестом
 * ({@code ResetClockListener}); дни убираются за собой.
 */
@AnalyticsModuleTest
class AnalyticsMaintenanceTest extends AnalyticsTestSupport {

    @Autowired
    AnalyticsMaintenance maintenance;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void purgeRemovesPastSaltsAndVisitors() {
        var older = onDay(LocalDate.of(1991, 3, 1));
        view("/");
        var yesterday = onDay(LocalDate.of(1991, 3, 9));
        view("/");
        ingest.record(pageView("/", null, null), "198.51.100.1", BROWSER);
        var today = onDay(yesterday.plusDays(1));
        view("/");
        assertThat(visitorRows(older) + visitorRows(yesterday)).isEqualTo(3);

        assertThat(maintenance.purgeExpired()).isEqualTo(3);

        for (var past : new LocalDate[] {older, yesterday}) {
            assertThat(visitorRows(past)).as("fingerprints of %s", past).isZero();
            assertThat(saltRows(past)).as("salt of %s", past).isZero();
        }
        assertThat(visitorRows(today)).isOne();
        assertThat(saltRows(today)).isOne();
        assertThat(maintenance.purgeExpired()).isZero();
    }

    /** Сегодняшние соль и отпечатки после уборки работают как прежде: тот же посетитель второй раз не считается. */
    @Test
    void todayKeepsCountingAfterPurge() {
        var yesterday = onDay(LocalDate.of(1991, 4, 1));
        view("/");
        var today = onDay(yesterday.plusDays(1));
        view("/");

        maintenance.purgeExpired();
        view("/");

        assertThat(count(today, "visitors", "all")).isOne();
        assertThat(count(today, "page_view", "/")).isEqualTo(2);
        assertThat(visitorRows(today)).isOne();
    }

    @Test
    void countsSurvivePurge() {
        var yesterday = onDay(LocalDate.of(1991, 5, 1));
        view("/");
        ingest.record(pageView("/", true, "ya.ru"), "198.51.100.1", IPHONE);
        ingest.record(cta("hero"), IP, BROWSER);
        var before = jdbc.sql("select count(*) from analytics.daily_count where day = :day")
                .param("day", yesterday)
                .query(Long.class)
                .single();
        onDay(yesterday.plusDays(1));

        maintenance.purgeExpired();

        assertThat(visitorRows(yesterday)).isZero();
        assertThat(count(yesterday, "visitors", "all")).isEqualTo(2);
        assertThat(count(yesterday, "page_view", "/")).isEqualTo(2);
        assertThat(count(yesterday, "cta_click", "hero")).isOne();
        assertThat(count(yesterday, "source", "ya.ru")).isOne();
        assertThat(count(yesterday, "device", "mobile")).isOne();
        assertThat(jdbc.sql("select count(*) from analytics.daily_count where day = :day")
                        .param("day", yesterday)
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }

    /** Вызов изнутри чужой транзакции — отказ: уборка фиксирует своё удаление сама. Ничего не удаляется. */
    @Test
    void refusesCallerTransaction() {
        var yesterday = onDay(LocalDate.of(1991, 6, 1));
        view("/");
        onDay(yesterday.plusDays(1));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> maintenance.purgeExpired()))
                .isInstanceOf(InternalException.class);
        assertThat(visitorRows(yesterday)).isOne();
        assertThat(saltRows(yesterday)).isOne();
    }
}
