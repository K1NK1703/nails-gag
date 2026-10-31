package gag.nails.common.time;

import gag.nails.common.error.ValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Период отчёта или выборки денежных фактов: дни в поясе салона, обе границы включительно. Общий для модулей, которые
 * отдают данные за период (записи, финансы), — правила одни. Проверки идут до любых вычислений с датами, поэтому
 * крайние значения ({@link LocalDate#MAX} и годы вне {@code timestamptz}) дают ошибку запроса, а не сбой.
 *
 * <ul>
 *   <li>{@code from} не раньше {@link #EARLIEST} — иначе {@link ValidationException} по полю {@code from};
 *   <li>{@code to} не позже «сегодня» плюс {@value #MAX_DAYS_AHEAD} дней, не раньше {@code from}, а весь период не
 *       длиннее {@value #MAX_DAYS} дней — иначе {@link ValidationException} по полю {@code to}.
 * </ul>
 */
public final class ReportPeriod {

    /** Самый ранний допустимый день. */
    public static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    /** Наибольшая длина периода в днях, считая обе границы: високосный год целиком. */
    public static final int MAX_DAYS = 366;

    /**
     * На сколько дней вперёд от «сегодня» может заходить период: отчёт может смотреть на год вперёд — будущие
     * повторяющиеся записи и запланированные визиты.
     */
    public static final int MAX_DAYS_AHEAD = 366;

    private final LocalDate from;
    private final LocalDate to;

    private ReportPeriod(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    /**
     * Проверенный период.
     *
     * @param from первый день периода, включительно; это же имя — поле {@link ValidationException}
     * @param to последний день периода, включительно; это же имя — поле {@link ValidationException}
     * @param today сегодняшний день в поясе салона
     * @throws ValidationException если период вне правил класса ({@code from} или {@code to})
     */
    public static ReportPeriod of(LocalDate from, LocalDate to, LocalDate today) {
        if (from.isBefore(EARLIEST)) {
            throw new ValidationException("from", "must not be before " + EARLIEST);
        }
        if (to.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ValidationException("to", "must be at most " + MAX_DAYS_AHEAD + " days after today");
        }
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ValidationException("to", "period must be at most " + MAX_DAYS + " days");
        }
        return new ReportPeriod(from, to);
    }

    public LocalDate from() {
        return from;
    }

    public LocalDate to() {
        return to;
    }

    /** Начало первого дня периода. */
    public Instant startIn(SalonTimeZone zone) {
        return zone.at(from, LocalTime.MIDNIGHT);
    }

    /** Начало дня после последнего: период — полуинтервал {@code [startIn, endIn)}. */
    public Instant endIn(SalonTimeZone zone) {
        return zone.at(to.plusDays(1), LocalTime.MIDNIGHT);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReportPeriod period && from.equals(period.from) && to.equals(period.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }

    /** Например, {@code 2026-01-01..2026-12-31}. */
    @Override
    public String toString() {
        return from + ".." + to;
    }
}
