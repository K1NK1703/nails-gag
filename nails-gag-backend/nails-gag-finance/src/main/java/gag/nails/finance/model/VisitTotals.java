package gag.nails.finance.model;

import java.math.BigDecimal;

/**
 * Итоги выполненных визитов периода. Скидка и надбавка считаются по каждому визиту отдельно, поэтому визит со скидкой
 * не гасит надбавку другого.
 *
 * @param count число визитов
 * @param priceSum прайс фактического состава визитов
 * @param discounts сумма по визитам {@code max(0, прайс − итог)}
 * @param surcharges сумма по визитам {@code max(0, итог − прайс)}
 * @param received сумма итогов визитов — сколько получено
 * @param averageCheck {@code received / count}, до копейки (половина — вверх); без визитов — ноль
 */
public record VisitTotals(
        int count,
        BigDecimal priceSum,
        BigDecimal discounts,
        BigDecimal surcharges,
        BigDecimal received,
        BigDecimal averageCheck) {}
