package gag.nails.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SalonTimeZoneTest {

    private final SalonTimeZone moscow = new SalonTimeZone(ZoneId.of("Europe/Moscow"));

    @Test
    void dayOfInstantIsTakenInSalonZone() {
        // 23:30 UTC 1 октября = 02:30 2 октября по Москве
        var lateEvening = Instant.parse("2026-10-01T23:30:00Z");

        assertThat(moscow.dayOf(lateEvening)).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void localDateTimeIsConvertedToInstant() {
        var start = moscow.at(LocalDate.of(2026, 10, 2), LocalTime.of(9, 30));

        assertThat(start).isEqualTo(Instant.parse("2026-10-02T06:30:00Z"));
    }

    @Test
    void localTimeOfInstant() {
        assertThat(moscow.timeOf(Instant.parse("2026-10-02T06:30:00Z"))).isEqualTo(LocalTime.of(9, 30));
    }
}
