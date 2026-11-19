package gag.nails.notifications.internal.queue;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.NotificationsProperties.QuietHoursWindow;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Перенос момента из тихих часов на их конец: окно по умолчанию 22:00–9:00 по Москве (UTC+3). Моменты заданы в UTC,
 * в комментариях — время салона.
 */
class QuietHoursTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    private static final QuietHoursWindow NIGHT = new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(9, 0));

    private final QuietHours quietHours = new QuietHours(NIGHT, MOSCOW);

    @Test
    void momentBeforeQuietHoursIsKept() {
        // 21:59
        var at = Instant.parse("2026-11-20T18:59:00Z");

        assertThat(quietHours.shift(at)).isEqualTo(at);
    }

    @Test
    void startOfQuietHoursMovesToNextMorning() {
        // 22:00 → 9:00 следующего дня
        assertThat(quietHours.shift(Instant.parse("2026-11-20T19:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-21T06:00:00Z"));
    }

    @Test
    void nightAfterMidnightMovesToSameMorning() {
        // 03:00 → 9:00 того же дня
        assertThat(quietHours.shift(Instant.parse("2026-11-21T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-21T06:00:00Z"));
    }

    @Test
    void endOfQuietHoursIsKept() {
        // 9:00 — тихие часы уже кончились
        var at = Instant.parse("2026-11-21T06:00:00Z");

        assertThat(quietHours.shift(at)).isEqualTo(at);
    }

    @Test
    void lastMomentOfQuietHoursMovesToTheirEnd() {
        // 8:59:59.999999999 → 9:00
        assertThat(quietHours.shift(Instant.parse("2026-11-21T05:59:59.999999999Z")))
                .isEqualTo(Instant.parse("2026-11-21T06:00:00Z"));
    }

    @Test
    void salonZoneDecides() {
        // 20:00 UTC — 23:00 по Москве (тихие часы), а в UTC тихих часов ещё нет.
        var at = Instant.parse("2026-11-20T20:00:00Z");

        assertThat(quietHours.shift(at)).isEqualTo(Instant.parse("2026-11-21T06:00:00Z"));
        assertThat(new QuietHours(NIGHT, ZoneId.of("UTC")).shift(at)).isEqualTo(at);
        // В Екатеринбурге (UTC+5) это 01:00 следующего дня — перенос на 9:00 того же утра, 04:00 UTC.
        assertThat(new QuietHours(NIGHT, ZoneId.of("Asia/Yekaterinburg")).shift(at))
                .isEqualTo(Instant.parse("2026-11-21T04:00:00Z"));
    }

    @Test
    void windowWithinOneDayMovesToItsEndSameDay() {
        var afternoon = new QuietHours(new QuietHoursWindow(LocalTime.of(13, 0), LocalTime.of(15, 0)), MOSCOW);

        // 14:00 → 15:00; 15:00 и 12:59 — как есть
        assertThat(afternoon.shift(Instant.parse("2026-11-20T11:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(afternoon.shift(Instant.parse("2026-11-20T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
        assertThat(afternoon.shift(Instant.parse("2026-11-20T09:59:00Z")))
                .isEqualTo(Instant.parse("2026-11-20T09:59:00Z"));
    }

    @Test
    void equalBoundsMeanNoQuietHours() {
        var none = new QuietHours(new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(22, 0)), MOSCOW);
        var at = Instant.parse("2026-11-20T19:00:00Z");

        assertThat(none.shift(at)).isEqualTo(at);
    }
}
