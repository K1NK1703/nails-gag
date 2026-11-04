package gag.nails.finance.internal.report;

import gag.nails.common.error.InternalException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Суммы отчётов — рубли с копейками: каждая итоговая сумма отчёта проходит через {@link #of} и отдаётся с двумя
 * знаками после запятой (ноль — {@code 0.00}, а не {@code 0}). Все суммы-источники (итоги и цены визитов, предоплаты,
 * ручные записи) хранятся с копейками, поэтому их суммы точны; округляется только средний чек.
 */
final class Money {

    /** Режим округления среднего чека: половина копейки — вверх (как принято в расчётах с покупателем). */
    static final RoundingMode AVERAGE_ROUNDING = RoundingMode.HALF_UP;

    private Money() {}

    /**
     * Та же сумма с двумя знаками после запятой.
     *
     * @throws InternalException если в сумме больше двух знаков после запятой: такой суммы в базе быть не может
     */
    static BigDecimal of(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InternalException("money amount " + amount + " has more than two fraction digits", e);
        }
    }

    /** {@code total / count} до копейки ({@link #AVERAGE_ROUNDING}); при нуле — ноль. */
    static BigDecimal average(BigDecimal total, int count) {
        return of(count == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(count), 2, AVERAGE_ROUNDING));
    }
}
