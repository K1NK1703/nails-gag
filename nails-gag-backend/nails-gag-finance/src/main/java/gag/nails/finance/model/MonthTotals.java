package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Итоги месяца — те же, что в {@link Summary}: доход, расход, прибыль.
 *
 * @param income доход от визитов, удержанные предоплаты и ручные доходы месяца
 * @param expenses ручные расходы месяца
 * @param profit {@code income − expenses}
 */
public record MonthTotals(YearMonth month, BigDecimal income, BigDecimal expenses, BigDecimal profit) {}
