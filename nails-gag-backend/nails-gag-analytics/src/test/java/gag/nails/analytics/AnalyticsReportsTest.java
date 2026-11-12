package gag.nails.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.analytics.model.AnalyticsSummary;
import gag.nails.analytics.model.DayTotals;
import gag.nails.analytics.model.KeyCount;
import gag.nails.analytics.model.Metric;
import gag.nails.analytics.model.MetricTotal;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Отчёты по счётчикам, записанным через приём. Дни — 2314 год, свои у каждого теста; дни сразу за границами периода
 * тоже с событиями — отчёт не должен их захватывать. Часы стоят на последнем дне с событиями: периоды теста — в
 * пределах {@code ReportPeriod} от этого «сегодня».
 */
@AnalyticsModuleTest
class AnalyticsReportsTest extends AnalyticsTestSupport {

    private static final Actor DEVELOPER = Actor.developer();

    @Autowired
    AnalyticsReports reports;

    @Test
    void summarySumsMetricsAndKeysInOrder() {
        onDay(LocalDate.of(2314, 1, 9));
        view("/");
        ingest.record(cta("price"), IP, BROWSER);
        ingest.record(pageView("/", true, "ya.ru"), IP, BROWSER);

        var first = onDay(LocalDate.of(2314, 1, 10));
        view("/");
        view("/booking");
        ingest.record(pageView("/", true, "www.ya.ru"), IP, IPHONE);
        ingest.record(cta("hero"), IP, BROWSER);
        ingest.record(cta("price"), IP, BROWSER);
        ingest.record(gallery(7), IP, BROWSER);
        ingest.record(gallery(12), IP, BROWSER);

        var second = onDay(LocalDate.of(2314, 1, 11));
        view("/booking");
        view("/terms");
        ingest.record(pageView("/", true, ""), IP, BROWSER);
        ingest.record(cta("price"), IP, BROWSER);
        ingest.record(cta("header"), IP, BROWSER);
        ingest.record(gallery(12), IP, BROWSER);
        ingest.record(map(), IP, BROWSER);

        onDay(LocalDate.of(2314, 1, 12));
        view("/terms");
        ingest.record(cta("hero"), IP, IPHONE);
        ingest.record(contact("telegram"), IP, BROWSER);
        ingest.record(faq("duration"), IP, BROWSER);

        // По убыванию числа, при равенстве — по ключу: price (2) раньше header (1), header раньше hero.
        assertThat(reports.summary(first, second, DEVELOPER))
                .isEqualTo(new AnalyticsSummary(
                        first,
                        second,
                        3,
                        List.of(
                                total(Metric.PAGE_VIEW, 6, key("/", 3), key("/booking", 2), key("/terms", 1)),
                                total(Metric.CTA_CLICK, 4, key("price", 2), key("header", 1), key("hero", 1)),
                                total(Metric.CONTACT_CLICK, 0),
                                total(Metric.GALLERY_OPEN, 3, key("12", 2), key("7", 1)),
                                total(Metric.FAQ_OPEN, 0),
                                total(Metric.MAP_OPEN, 1, key("map", 1)),
                                total(Metric.SOURCE, 2, key("direct", 1), key("ya.ru", 1)),
                                total(Metric.DEVICE, 2, key("desktop", 1), key("mobile", 1)))));
    }

    /** Посетитель трёх дней посчитан трижды: отпечаток живёт сутки, уникальных за период отчёт не знает. */
    @Test
    void visitorsAreSumOfDailyUniques() {
        var first = onDay(LocalDate.of(2314, 2, 1));
        view("/");
        view("/");
        var second = onDay(first.plusDays(1));
        view("/");
        ingest.record(pageView("/", null, null), IP, IPHONE);
        var third = onDay(second.plusDays(1));
        view("/");

        var summary = reports.summary(first, third, DEVELOPER);

        assertThat(summary.visitors()).isEqualTo(4);
        assertThat(summary.metrics()).extracting(MetricTotal::metric).doesNotContain(Metric.VISITORS);
        assertThat(summary.metrics().getFirst()).isEqualTo(total(Metric.PAGE_VIEW, 5, key("/", 5)));
        assertThat(reports.daily(first, third, DEVELOPER))
                .extracting(DayTotals::visitors)
                .containsExactly(1L, 2L, 1L);
    }

    @Test
    void dailyHasEveryDayWithZeros() {
        onDay(LocalDate.of(2314, 3, 9));
        view("/");
        ingest.record(contact("telegram"), IP, BROWSER);

        var first = onDay(LocalDate.of(2314, 3, 10));
        view("/");
        view("/terms");
        ingest.record(cta("hero"), IP, BROWSER);
        ingest.record(contact("telegram"), IP, BROWSER);
        ingest.record(gallery(3), IP, BROWSER);
        ingest.record(faq("duration"), IP, BROWSER);
        take(first.plusDays(1));
        take(first.plusDays(2));
        var fourth = onDay(first.plusDays(3));
        ingest.record(pageView("/booking", true, "ya.ru"), IP, IPHONE);
        ingest.record(contact("channel"), IP, IPHONE);
        ingest.record(contact("channel"), IP, IPHONE);
        var last = take(first.plusDays(4));

        onDay(last.plusDays(1));
        view("/");
        ingest.record(cta("price"), IP, BROWSER);

        assertThat(reports.daily(first, last, DEVELOPER))
                .containsExactly(
                        new DayTotals(first, 1, 2, 1, 1),
                        new DayTotals(first.plusDays(1), 0, 0, 0, 0),
                        new DayTotals(first.plusDays(2), 0, 0, 0, 0),
                        new DayTotals(fourth, 1, 1, 0, 2),
                        new DayTotals(last, 0, 0, 0, 0));
    }

    @Test
    void emptyPeriodListsEveryMetricWithZero() {
        var first = take(LocalDate.of(2314, 4, 1));
        var last = take(first.plusDays(1));
        at(last, LocalTime.NOON);

        var summary = reports.summary(first, last, DEVELOPER);

        assertThat(summary)
                .isEqualTo(new AnalyticsSummary(
                        first,
                        last,
                        0,
                        List.of(
                                total(Metric.PAGE_VIEW, 0),
                                total(Metric.CTA_CLICK, 0),
                                total(Metric.CONTACT_CLICK, 0),
                                total(Metric.GALLERY_OPEN, 0),
                                total(Metric.FAQ_OPEN, 0),
                                total(Metric.MAP_OPEN, 0),
                                total(Metric.SOURCE, 0),
                                total(Metric.DEVICE, 0))));
        assertThat(reports.daily(first, last, DEVELOPER))
                .containsExactly(new DayTotals(first, 0, 0, 0, 0), new DayTotals(last, 0, 0, 0, 0));
    }

    /**
     * Правила {@code ReportPeriod}: не длиннее 366 дней, {@code from} не раньше 2000 года, {@code to} не раньше
     * {@code from} и не дальше «сегодня» плюс 366 дней; пустые границы — ошибка по своему полю.
     */
    @Test
    void periodIsChecked() {
        var today = LocalDate.of(2314, 6, 1);
        at(today, LocalTime.NOON);
        var from = LocalDate.of(2314, 1, 1);
        var longestTo = from.plusDays(365);
        var tooLong = longestTo.plusDays(1);
        var lastDay = today.plusDays(366);

        assertThat(reports.summary(from, longestTo, DEVELOPER).metrics()).hasSize(8);
        assertThat(reports.daily(from, longestTo, DEVELOPER)).hasSize(366);
        assertThat(reports.daily(lastDay, lastDay, DEVELOPER)).hasSize(1);
        assertInvalid(() -> reports.summary(from, tooLong, DEVELOPER), "to");
        assertInvalid(() -> reports.daily(from, tooLong, DEVELOPER), "to");
        assertInvalid(() -> reports.summary(today, today.minusDays(1), DEVELOPER), "to");
        assertInvalid(() -> reports.daily(today, today.minusDays(1), DEVELOPER), "to");
        assertInvalid(() -> reports.summary(lastDay, lastDay.plusDays(1), DEVELOPER), "to");
        assertInvalid(() -> reports.daily(lastDay, lastDay.plusDays(1), DEVELOPER), "to");
        var beforeEarliest = LocalDate.of(1999, 12, 31);
        assertInvalid(() -> reports.summary(beforeEarliest, beforeEarliest.plusDays(1), DEVELOPER), "from");
        assertInvalid(() -> reports.daily(beforeEarliest, beforeEarliest.plusDays(1), DEVELOPER), "from");
        assertInvalid(() -> reports.summary(null, today, DEVELOPER), "from");
        assertInvalid(() -> reports.summary(today, null, DEVELOPER), "to");
        assertInvalid(() -> reports.daily(null, today, DEVELOPER), "from");
        assertInvalid(() -> reports.daily(today, null, DEVELOPER), "to");
    }

    /**
     * Читает разработчик (встроенный набор), мастер с личным переопределением {@code ANALYTICS READ} (его итоговые
     * уровни — набор мастера плюс чтение раздела) и актор с одним этим разделом. Встроенный мастер, клиент и
     * сотрудник других разделов получают отказ — раньше проверки периода.
     */
    @Test
    void reportsNeedAnalyticsRead() {
        var day = onDay(LocalDate.of(2314, 5, 1));
        view("/");

        var masterWithOverride = new EnumMap<Section, AccessLevel>(Section.class);
        masterWithOverride.putAll(Actor.masterAccess());
        masterWithOverride.put(Section.ANALYTICS, AccessLevel.READ);
        var readers = List.of(
                DEVELOPER,
                Actor.of(UUID.randomUUID(), null, masterWithOverride),
                Actor.of(null, null, Map.of(Section.ANALYTICS, AccessLevel.READ)));
        for (var reader : readers) {
            assertThat(reports.summary(day, day, reader).visitors()).isOne();
            assertThat(reports.daily(day, day, reader)).containsExactly(new DayTotals(day, 1, 1, 0, 0));
        }
        var strangers = List.of(
                Actor.master(),
                Actor.client(UUID.randomUUID()),
                Actor.of(null, null, Map.of(Section.FINANCE, AccessLevel.WRITE)));
        for (var stranger : strangers) {
            assertThatThrownBy(() -> reports.summary(day, day, stranger)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reports.daily(day, day, stranger)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reports.summary(day, day.minusDays(1), stranger))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    private static MetricTotal total(Metric metric, long total, KeyCount... keys) {
        return new MetricTotal(metric, total, List.of(keys));
    }

    private static KeyCount key(String key, long count) {
        return new KeyCount(key, count);
    }

    private static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo(field));
    }
}
