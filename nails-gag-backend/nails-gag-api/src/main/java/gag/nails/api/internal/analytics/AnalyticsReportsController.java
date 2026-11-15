package gag.nails.api.internal.analytics;

import gag.nails.analytics.AnalyticsReports;
import gag.nails.analytics.model.AnalyticsSummary;
import gag.nails.analytics.model.DayTotals;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.ReportPeriod;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Отчёты счётчика посещений (спецификация счётчика, §3.4, §4) по дневным счётчикам. Нужен уровень чтения раздела
 * «Статистика посещений», его проверяет модуль: сотруднику без него — {@code 403 common.access_denied}, клиенту —
 * {@code 404 common.not_found}, без сессии — {@code 401}. Период {@code from}/{@code to} — дни в поясе салона, обе
 * границы включительно, по правилам {@link ReportPeriod} — его проверяет модуль ({@code 400} с полем {@code from} или
 * {@code to}). Ответы — {@code Cache-Control: no-store}; метрики в JSON — имена Java ({@code PAGE_VIEW}, …).
 */
@RestController
@RequestMapping("/api/analytics")
class AnalyticsReportsController {

    private final AnalyticsReports reports;

    AnalyticsReportsController(AnalyticsReports reports) {
        this.reports = reports;
    }

    /**
     * Сводка за период: посетители — сумма уникальных по дням (не уникальные за период), и все остальные метрики в
     * порядке перечисления, метрика без событий — с нулём и без ключей; у метрики — итог и до 100 самых частых ключей.
     */
    @GetMapping("/summary")
    AnalyticsSummary summary(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        return reports.summary(from, to, actor);
    }

    /**
     * Итоги по дням: каждый день периода по порядку — посетители, просмотры, нажатия записи и переходы к контактам;
     * день без событий — нули.
     */
    @GetMapping("/daily")
    List<DayTotals> daily(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        return reports.daily(from, to, actor);
    }
}
