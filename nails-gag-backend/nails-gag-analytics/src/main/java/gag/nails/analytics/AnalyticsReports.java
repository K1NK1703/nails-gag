package gag.nails.analytics;

import gag.nails.analytics.model.AnalyticsSummary;
import gag.nails.analytics.model.DayTotals;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.ReportPeriod;
import java.time.LocalDate;
import java.util.List;

/**
 * Отчёты счётчика посещений по дневным счётчикам. Дни — в поясе салона; период — обе границы включительно, по
 * правилам {@link ReportPeriod}. Нужен уровень чтения раздела «Статистика посещений» ({@link Section#ANALYTICS}),
 * иначе {@link AccessDeniedException} — она проверяется раньше периода. Каждый отчёт — один запрос к базе.
 */
public interface AnalyticsReports {

    /**
     * Сводка за период: посетители (сумма дневных уникальных, а не уникальные за период) и все остальные метрики — в
     * порядке {@link gag.nails.analytics.model.Metric}, метрика без событий — с нулём и без ключей. У метрики — итог
     * по всем ключам и до 100 самых частых ключей.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) граница пуста или период вне правил
     *     {@link ReportPeriod}
     */
    AnalyticsSummary summary(LocalDate from, LocalDate to, Actor actor);

    /**
     * Итоги по дням: каждый день периода по порядку, день без событий — нули.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) граница пуста или период вне правил
     *     {@link ReportPeriod}
     */
    List<DayTotals> daily(LocalDate from, LocalDate to, Actor actor);
}
