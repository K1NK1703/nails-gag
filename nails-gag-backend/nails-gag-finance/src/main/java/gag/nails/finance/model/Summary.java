package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Сводка за период: доход от визитов и удержанных предоплат из модуля записей и ручные доходы и расходы.
 *
 * @param from первый день периода, включительно
 * @param to последний день периода, включительно
 * @param incomeByCategory ручные доходы по категориям — в порядке категорий в списке доходов
 * @param expenseByCategory ручные расходы по категориям — в порядке категорий в списке расходов
 * @param income {@code visits.received} + удержанные предоплаты + ручные доходы
 * @param expenses ручные расходы
 * @param profit {@code income − expenses}; может быть отрицательной
 */
public record Summary(
        LocalDate from,
        LocalDate to,
        VisitTotals visits,
        KeptPrepayments keptPrepayments,
        List<CategoryTotal> incomeByCategory,
        List<CategoryTotal> expenseByCategory,
        BigDecimal income,
        BigDecimal expenses,
        BigDecimal profit) {

    public Summary {
        incomeByCategory = List.copyOf(incomeByCategory);
        expenseByCategory = List.copyOf(expenseByCategory);
    }
}
