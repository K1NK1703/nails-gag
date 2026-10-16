package gag.nails.api.internal.http;

import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Период в параметрах запроса (спецификация API, §4.1): {@code from} и {@code to} включительно, {@code to} не раньше
 * {@code from}, не больше {@value #MAX_DAYS} дней. Иначе {@code 400 common.validation} с полем {@code to}.
 */
public final class PeriodParams {

    /** Самый длинный период в днях, включая оба конца: квартал с запасом. */
    public static final int MAX_DAYS = 93;

    private PeriodParams() {}

    /**
     * @throws ValidationException если {@code to} раньше {@code from} или период длиннее {@value #MAX_DAYS} дней
     */
    public static void check(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ValidationException("to", "period must not exceed " + MAX_DAYS + " days");
        }
    }
}
