package gag.nails.analytics.internal.report;

import gag.nails.analytics.AnalyticsReports;
import gag.nails.analytics.model.AnalyticsSummary;
import gag.nails.analytics.model.DayTotals;
import gag.nails.analytics.model.KeyCount;
import gag.nails.analytics.model.Metric;
import gag.nails.analytics.model.MetricTotal;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.ReportPeriod;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отчёты по дневным счётчикам: каждый отчёт — один запрос ({@link PeriodCounts}), раскладка по метрикам и дням — в
 * памяти. Объём ответа ограничен защитой приёма: у метрики не больше {@code max-keys-per-metric} ключей за день (плюс
 * {@code other}), то есть за год под постоянной атакой — тысячи ключей у метрики, обычно — десятки; ключи сводки
 * отдаются все, без усечения.
 *
 * <p>Транзакция только для чтения с уровнем изоляции по умолчанию ({@code READ COMMITTED} в PostgreSQL), а не
 * {@code REPEATABLE READ}, как у отчётов финансов: там отчёт читает несколько запросов, и им нужен общий снимок, а
 * здесь запрос один, и один оператор PostgreSQL и так видит один снимок на всех уровнях. «Только чтение» не даёт
 * Hibernate сбрасывать контекст и запрещает запись на уровне базы. Внутри чужой транзакции отчёт к ней присоединяется
 * и читает с её уровнем.
 */
@Service
@Transactional(readOnly = true)
class DefaultAnalyticsReports implements AnalyticsReports {

    /** Метрики сводки — все, кроме посетителей: те отдаются отдельным полем. Порядок — порядок {@link Metric}. */
    private static final List<Metric> SUMMARY_METRICS = Arrays.stream(Metric.values())
            .filter(metric -> metric != Metric.VISITORS)
            .toList();

    private static final Set<Metric> DAILY_METRICS =
            EnumSet.of(Metric.VISITORS, Metric.PAGE_VIEW, Metric.CTA_CLICK, Metric.CONTACT_CLICK);

    /**
     * Ключи — по убыванию числа, при равенстве — по ключу ({@link String#compareTo}): порядок не зависит от правил
     * сравнения строк в базе; ключи — ASCII, и это просто порядок байтов.
     */
    private static final Comparator<KeyCount> KEY_ORDER =
            Comparator.comparingLong(KeyCount::count).reversed().thenComparing(KeyCount::key);

    private final PeriodCounts counts;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultAnalyticsReports(PeriodCounts counts, SalonTimeZone salonTimeZone, Clock clock) {
        this.counts = counts;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public AnalyticsSummary summary(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.ANALYTICS);
        var period = period(from, to);
        var totals = new EnumMap<Metric, Long>(Metric.class);
        var keys = new EnumMap<Metric, List<KeyCount>>(Metric.class);
        for (var sum : counts.keySums(period.from(), period.to())) {
            totals.put(sum.metric(), sum.total());
            keys.computeIfAbsent(sum.metric(), metric -> new ArrayList<>()).add(new KeyCount(sum.key(), sum.count()));
        }
        var metrics = SUMMARY_METRICS.stream()
                .map(metric -> new MetricTotal(
                        metric,
                        totals.getOrDefault(metric, 0L),
                        keys.getOrDefault(metric, List.of()).stream()
                                .sorted(KEY_ORDER)
                                .toList()))
                .toList();
        return new AnalyticsSummary(period.from(), period.to(), totals.getOrDefault(Metric.VISITORS, 0L), metrics);
    }

    @Override
    public List<DayTotals> daily(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.ANALYTICS);
        var period = period(from, to);
        var sums = new HashMap<LocalDate, Map<Metric, Long>>();
        for (var sum : counts.daySums(period.from(), period.to(), DAILY_METRICS)) {
            sums.computeIfAbsent(sum.day(), day -> new EnumMap<>(Metric.class)).put(sum.metric(), sum.count());
        }
        return period.from()
                .datesUntil(period.to().plusDays(1))
                .map(day -> {
                    var ofDay = sums.getOrDefault(day, Map.of());
                    return new DayTotals(
                            day,
                            ofDay.getOrDefault(Metric.VISITORS, 0L),
                            ofDay.getOrDefault(Metric.PAGE_VIEW, 0L),
                            ofDay.getOrDefault(Metric.CTA_CLICK, 0L),
                            ofDay.getOrDefault(Metric.CONTACT_CLICK, 0L));
                })
                .toList();
    }

    /**
     * Проверенный период. Пустые границы — ошибка запроса по своему полю: вызывающий код на Java может передать их
     * мимо проверок HTTP-слоя.
     */
    private ReportPeriod period(@Nullable LocalDate from, @Nullable LocalDate to) {
        if (from == null) {
            throw new ValidationException("from", "must not be null");
        }
        if (to == null) {
            throw new ValidationException("to", "must not be null");
        }
        return ReportPeriod.of(from, to, salonTimeZone.today(clock));
    }
}
