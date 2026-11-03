package gag.nails.finance.internal.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Суммы отчётов — рубли с копейками, всегда с двумя знаками после запятой: ноль — {@code 0.00}, а не {@code 0}. Все
 * суммы-источники (итоги и цены визитов, предоплаты, ручные записи) хранятся с копейками, поэтому их суммы точны;
 * округляется только средний чек.
 */
final class Money {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** Режим округления среднего чека: половина копейки — вверх (как принято в расчётах с покупателем). */
    static final RoundingMode AVERAGE_ROUNDING = RoundingMode.HALF_UP;

    private Money() {}

    /**
     * Та же сумма с двумя знаками после запятой.
     *
     * @throws ArithmeticException если в сумме больше двух знаков после запятой: такой суммы в базе быть не может
     */
    static BigDecimal of(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** {@code total / count} до копейки ({@link #AVERAGE_ROUNDING}); при нуле — ноль. */
    static BigDecimal average(BigDecimal total, int count) {
        return count == 0 ? ZERO : total.divide(BigDecimal.valueOf(count), 2, AVERAGE_ROUNDING);
    }
}
