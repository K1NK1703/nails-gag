package gag.nails.finance;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.time.ReportPeriod;
import gag.nails.finance.model.JournalFilter;
import gag.nails.finance.model.JournalItem;
import gag.nails.finance.model.MonthTotals;
import gag.nails.finance.model.ServicesReport;
import gag.nails.finance.model.Summary;
import java.time.LocalDate;
import java.util.List;

/**
 * Отчёты финансов из двух источников: доход от выполненных визитов и удержанных предоплат — денежные факты модуля
 * записей, ручные доходы и расходы — записи финансов. Суммы — в рублях с копейками; дни — в поясе салона; период —
 * обе границы включительно, по правилам {@link ReportPeriod}. Визит относится к дню визита, удержанная предоплата —
 * к дню отмены или неявки, ручная запись — к дню операции. Нужен уровень чтения раздела «Финансы»
 * ({@link AccessDeniedException}); клиента отчёты не содержат.
 */
public interface FinanceReports {

    /**
     * Сводка за период: визиты (число, прайс, скидки, надбавки, получено, средний чек), удержанные предоплаты, ручные
     * доходы и расходы по категориям (и скрытым), доход, расход и прибыль.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) период вне правил {@link ReportPeriod}
     */
    Summary summary(LocalDate from, LocalDate to, Actor actor);

    /**
     * Доход, расход и прибыль по месяцам календарного года: ровно двенадцать месяцев по порядку, месяц без операций —
     * нули. Год может заходить за предел {@link ReportPeriod} («сегодня» плюс {@value ReportPeriod#MAX_DAYS_AHEAD}
     * дней) — дни после предела не читаются: операций там быть не может, дни записей и фактов ограничены тем же
     * пределом.
     *
     * @throws ValidationException (поле {@code year}) год раньше {@link ReportPeriod#EARLIEST} или целиком позже
     *     предела
     */
    List<MonthTotals> monthly(int year, Actor actor);

    /**
     * Услуги и дополнения фактического состава выполненных визитов периода: сколько раз оказаны и сумма по прайсу;
     * число визитов и средний чек.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) период вне правил {@link ReportPeriod}
     */
    ServicesReport services(LocalDate from, LocalDate to, Actor actor);

    /**
     * Журнал операций периода: визиты, удержанные предоплаты, ручные доходы и расходы. Новые дни первыми; внутри дня —
     * визиты (позже начавшиеся первыми), затем удержанные предоплаты (позже отменённые или отмеченные неявкой первыми),
     * затем ручные записи обоих видов (позже внесённые первыми). Порядок однозначен: страницы не перекрываются.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) период пуст или вне правил {@link ReportPeriod};
     *     ({@code types[].<iterable element>} — путь Bean Validation к элементу набора) среди видов есть пустой
     */
    Paged<JournalItem> journal(JournalFilter filter, Paging paging, Actor actor);
}
