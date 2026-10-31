package gag.nails.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ReportPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    void wholeLeapYearIsAllowed() {
        var period = ReportPeriod.of(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 12, 31), TODAY);

        assertThat(period.from()).isEqualTo(ReportPeriod.EARLIEST);
        assertThat(period.to()).isEqualTo(LocalDate.of(2000, 12, 31));
    }

    @Test
    void fromIsNotBefore2000() {
        rejected(LocalDate.of(1999, 12, 31), LocalDate.of(2000, 1, 1), "from");
        rejected(LocalDate.MIN, LocalDate.of(2000, 1, 1), "from");
    }

    @Test
    void toIsAtMost366DaysAhead() {
        var lastAllowed = TODAY.plusDays(ReportPeriod.MAX_DAYS_AHEAD);
        assertThat(ReportPeriod.of(lastAllowed, lastAllowed, TODAY).to()).isEqualTo(lastAllowed);

        rejected(lastAllowed.plusDays(1), lastAllowed.plusDays(1), "to");
        rejected(TODAY, LocalDate.MAX, "to");
    }

    @Test
    void toIsNotBeforeFromAndPeriodIsAtMost366Days() {
        rejected(TODAY, TODAY.minusDays(1), "to");
        var from = LocalDate.of(2026, 1, 1);
        assertThat(ReportPeriod.of(from, from.plusDays(365), TODAY).to()).isEqualTo(LocalDate.of(2027, 1, 1));
        rejected(from, from.plusDays(366), "to");
    }

    @Test
    void boundsAreSalonDays() {
        var zone = new SalonTimeZone(ZoneId.of("Europe/Moscow"));
        var period = ReportPeriod.of(TODAY, TODAY, TODAY);

        assertThat(period.start(zone)).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
        assertThat(period.end(zone)).isEqualTo(Instant.parse("2026-10-01T21:00:00Z"));
    }

    private static void rejected(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> ReportPeriod.of(from, to, TODAY))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo(field));
    }
}
