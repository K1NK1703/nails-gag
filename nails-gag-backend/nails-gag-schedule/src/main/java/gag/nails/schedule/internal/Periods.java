package gag.nails.schedule.internal;

import gag.nails.common.error.ValidationException;
import java.time.LocalDate;

/** Проверки периодов дат. */
final class Periods {

    private Periods() {}

    static void requireOrdered(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
    }
}
