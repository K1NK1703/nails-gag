package gag.nails.api.internal.finance;

import gag.nails.api.internal.http.PageDefaults;
import gag.nails.common.actor.Actor;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.time.ReportPeriod;
import gag.nails.finance.FinanceReports;
import gag.nails.finance.model.JournalFilter;
import gag.nails.finance.model.JournalItem;
import gag.nails.finance.model.JournalType;
import gag.nails.finance.model.MonthTotals;
import gag.nails.finance.model.ServicesReport;
import gag.nails.finance.model.Summary;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Отчёты финансов (спецификация финансов, §4.4, §5): доход от выполненных визитов и удержанных предоплат вместе с
 * ручными доходами и расходами. Нужен уровень чтения раздела «Финансы», его проверяет модуль; на корректный запрос
 * клиенту адреса отвечают {@code 404 common.not_found}. Суммы — в рублях с копейками; период {@code from}/{@code to}
 * — дни в поясе салона, обе границы включительно, по правилам {@link ReportPeriod} — его проверяет модуль
 * ({@code 400} с полем {@code from} или {@code to}).
 */
@RestController
@RequestMapping("/api/finance")
class FinanceReportsController {

    private final FinanceReports reports;

    FinanceReportsController(FinanceReports reports) {
        this.reports = reports;
    }

    /**
     * Сводка за период: визиты (число, прайс, скидки, надбавки, получено, средний чек), удержанные предоплаты, ручные
     * доходы и расходы по категориям, доход, расход и прибыль.
     */
    @GetMapping("/summary")
    Summary summary(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        return reports.summary(from, to, actor);
    }

    /**
     * Доход, расход и прибыль по двенадцати месяцам календарного года; месяц без операций — нули. Год раньше
     * {@link ReportPeriod#EARLIEST} или целиком позже предела {@link ReportPeriod} — {@code 400} с полем {@code year}.
     */
    @GetMapping("/monthly")
    List<MonthTotals> monthly(@RequestParam int year, Actor actor) {
        return reports.monthly(year, actor);
    }

    /**
     * Услуги и дополнения фактического состава выполненных визитов периода: сколько раз оказаны и сумма по прайсу;
     * число визитов и средний чек.
     */
    @GetMapping("/services")
    ServicesReport services(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        return reports.services(from, to, actor);
    }

    /**
     * Журнал операций периода постранично: новые дни первыми, внутри дня — визиты, удержанные предоплаты, затем ручные
     * записи. Виды операций — повтором параметра ({@code type=visit&type=kept-prepayment}) или через запятую; без него
     * — все виды.
     * {@code categoryId} оставляет только ручные записи этой категории.
     */
    @GetMapping("/journal")
    Paged<JournalItem> journal(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) @Nullable Set<JournalType> type,
            @RequestParam(required = false) @Nullable UUID categoryId,
            @RequestParam(defaultValue = PageDefaults.FIRST_PAGE) int page,
            @RequestParam(defaultValue = PageDefaults.SIZE) int size,
            Actor actor) {
        return reports.journal(new JournalFilter(from, to, type, categoryId), new Paging(page, size), actor);
    }
}
