package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Отчёт по услугам за период.
 *
 * @param from первый день периода, включительно
 * @param to последний день периода, включительно
 * @param visits число выполненных визитов
 * @param averageCheck средний чек, как в {@link VisitTotals#averageCheck}
 * @param services основные услуги, затем дополнения; внутри вида — чаще оказанные первыми, при равенстве — с большей
 *     суммой по прайсу, затем по идентификатору услуги
 */
public record ServicesReport(
        LocalDate from, LocalDate to, int visits, BigDecimal averageCheck, List<ServiceTotal> services) {

    public ServicesReport {
        services = List.copyOf(services);
    }
}
