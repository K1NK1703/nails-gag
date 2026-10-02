package gag.nails.schedule.internal;

import gag.nails.common.error.ValidationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Проверки и арифметика периодов дат; неделя — с понедельника (ISO). */
final class Periods {

    private Periods() {}

    static void requireOrdered(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationException("to", "must not be before from");
        }
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    /** Понедельники всех недель, задевающих период, по порядку. */
    static List<LocalDate> mondays(LocalDate from, LocalDate to) {
        var mondays = new ArrayList<LocalDate>();
        for (var monday = mondayOf(from); !monday.isAfter(to); monday = monday.plusWeeks(1)) {
            mondays.add(monday);
        }
        return List.copyOf(mondays);
    }

    /** Все даты периода по порядку, обе границы включительно. */
    static List<LocalDate> days(LocalDate from, LocalDate to) {
        return from.datesUntil(to.plusDays(1)).toList();
    }
}
