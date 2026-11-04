package gag.nails.finance.internal.report;

import gag.nails.booking.model.VisitFact;
import gag.nails.finance.model.VisitTotals;
import java.math.BigDecimal;
import java.util.List;

/** Итоги визитов из денежных фактов модуля записей. */
final class VisitStats {

    private VisitStats() {}

    /**
     * Скидка и надбавка — по каждому визиту отдельно: {@code max(0, прайс − итог)} и {@code max(0, итог − прайс)}.
     * Сумма по всем визитам сразу дала бы чистую разницу, и надбавка одного визита погасила бы скидку другого.
     */
    static VisitTotals totals(List<VisitFact> visits) {
        var priceSum = BigDecimal.ZERO;
        var discounts = BigDecimal.ZERO;
        var surcharges = BigDecimal.ZERO;
        var received = BigDecimal.ZERO;
        for (var visit : visits) {
            var difference = visit.priceSum().subtract(visit.finalAmount());
            priceSum = priceSum.add(visit.priceSum());
            received = received.add(visit.finalAmount());
            if (difference.signum() > 0) {
                discounts = discounts.add(difference);
            } else {
                surcharges = surcharges.add(difference.negate());
            }
        }
        return new VisitTotals(
                visits.size(),
                Money.of(priceSum),
                Money.of(discounts),
                Money.of(surcharges),
                Money.of(received),
                Money.average(received, visits.size()));
    }
}
