package gag.nails.booking.internal.booking;

import gag.nails.booking.model.PrepaymentOutcome;

/**
 * Ручное переопределение исхода полученной предоплаты: только удержана или возвращена. «В счёт визита» мастер не
 * выбирает — это исход активной или выполненной записи, — поэтому в типе его нет. Значения совпадают с {@code CHECK}
 * колонки {@code prepayment_outcome_override}.
 */
public enum PrepaymentOverride {
    KEPT(PrepaymentOutcome.KEPT),
    RETURNED(PrepaymentOutcome.RETURNED);

    private final PrepaymentOutcome outcome;

    PrepaymentOverride(PrepaymentOutcome outcome) {
        this.outcome = outcome;
    }

    public PrepaymentOutcome outcome() {
        return outcome;
    }
}
