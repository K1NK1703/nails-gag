package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryKind;
import java.math.BigDecimal;
import java.time.YearMonth;

/** Сумма записей одного вида за месяц ({@link EntryReports#sumByMonth}). */
public record MonthSum(YearMonth month, EntryKind kind, BigDecimal amount) {

    /** Для выборки: год и месяц дня операции приходят из SQL числами. */
    public MonthSum(Integer year, Integer month, EntryKind kind, BigDecimal amount) {
        this(YearMonth.of(year, month), kind, amount);
    }
}
