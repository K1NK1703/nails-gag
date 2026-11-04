package gag.nails.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.analytics.command.RecordEvent;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.testsupport.MutableClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Общее для тестов приёма: события, чтение счётчиков и уборка. Тест берёт свои далёкие дни через {@link #onDay}: день
 * должен быть пуст (чужих строк в нём нет), часы ставятся на полдень этого дня в поясе салона, а после теста строки
 * всех взятых дней удаляются — данные фиксируются на самом деле.
 */
abstract class AnalyticsTestSupport {

    static final String IP = "203.0.113.7";
    static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
            + " (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

    private static final String ROWS = """
            select (select count(*) from analytics.daily_count where day = :day)
                 + (select count(*) from analytics.visitor where day = :day)
                 + (select count(*) from analytics.day_salt where day = :day)""";

    @Autowired
    AnalyticsIngest ingest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    private final List<LocalDate> days = new ArrayList<>();

    @AfterEach
    void removeDays() {
        if (days.isEmpty()) {
            return;
        }
        for (var table : List.of("daily_count", "visitor", "day_salt")) {
            jdbc.sql("delete from analytics." + table + " where day in (:days)")
                    .param("days", days)
                    .update();
        }
        days.clear();
    }

    /** Берёт пустой день теста и ставит часы на его полдень по времени салона. */
    LocalDate onDay(LocalDate day) {
        take(day);
        at(day, LocalTime.NOON);
        return day;
    }

    /** Берёт пустой день теста, не трогая часы. */
    LocalDate take(LocalDate day) {
        assertThat(rows(day)).as("rows of %s before the test", day).isZero();
        days.add(day);
        return day;
    }

    void at(LocalDate day, LocalTime time) {
        clock.set(salonTimeZone.at(day, time));
    }

    long count(LocalDate day, String metric, String key) {
        return jdbc.sql("select count from analytics.daily_count where day = :day and metric = :metric and key = :key")
                .param("day", day)
                .param("metric", metric)
                .param("key", key)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Ключи метрики за день и их числа. */
    Map<String, Long> counts(LocalDate day, String metric) {
        return jdbc
                .sql("select key, count from analytics.daily_count where day = :day and metric = :metric")
                .param("day", day)
                .param("metric", metric)
                .query((rs, n) -> Map.entry(rs.getString("key"), rs.getLong("count")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum, TreeMap::new));
    }

    /** Все строки дня во всех таблицах модуля. */
    long rows(LocalDate day) {
        return jdbc.sql(ROWS).param("day", day).query(Long.class).single();
    }

    long visitorRows(LocalDate day) {
        return jdbc.sql("select count(*) from analytics.visitor where day = :day")
                .param("day", day)
                .query(Long.class)
                .single();
    }

    long saltRows(LocalDate day) {
        return jdbc.sql("select count(*) from analytics.day_salt where day = :day")
                .param("day", day)
                .query(Long.class)
                .single();
    }

    void view(String path) {
        ingest.record(pageView(path, null, null), IP, BROWSER);
    }

    static RecordEvent pageView(String path, @Nullable Boolean entry, @Nullable String referrer) {
        return new RecordEvent("page_view", path, null, null, null, null, entry, referrer);
    }

    static RecordEvent cta(String place) {
        return new RecordEvent("cta_click", null, place, null, null, null, null, null);
    }

    static RecordEvent contact(String channel) {
        return new RecordEvent("contact_click", null, null, channel, null, null, null, null);
    }

    static RecordEvent gallery(int index) {
        return new RecordEvent("gallery_open", null, null, null, index, null, null, null);
    }

    static RecordEvent faq(String id) {
        return new RecordEvent("faq_open", null, null, null, null, id, null, null);
    }

    static RecordEvent map() {
        return new RecordEvent("map_open", null, null, null, null, null, null, null);
    }
}
