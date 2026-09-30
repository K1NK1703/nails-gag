package gag.nails.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Часовой пояс салона: границы «дня» и локальное время слотов считаются в нём, хранение — в {@link Instant}. */
public record SalonTimeZone(ZoneId zone) {

    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    public LocalTime timeOf(Instant instant) {
        return instant.atZone(zone).toLocalTime();
    }

    public Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(zone).toInstant();
    }
}
