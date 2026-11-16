package gag.nails.notifications.internal.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.NotificationsProperties.QuietHoursWindow;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.internal.reminder.ReminderSchedule.Reminder;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.ReminderSettings;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Расчёт напоминаний (спека §3.4): салон в Москве (UTC+3), тихие часы 22:00–9:00, сейчас — четверг 1 октября 2026,
 * 9:00. Моменты заданы в UTC, в комментариях — время салона.
 */
class ReminderScheduleTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final QuietHoursWindow NIGHT = new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(9, 0));

    /** Четверг, 9:00. */
    private static final Instant NOW = Instant.parse("2026-10-01T06:00:00Z");

    /** Понедельник 5 октября, 10:00. */
    private static final Instant MONDAY_10 = Instant.parse("2026-10-05T07:00:00Z");

    private static final ReminderSettings DAY_BEFORE_ONLY = new ReminderSettings(LocalTime.NOON, null);
    private static final ReminderSettings TWO_HOURS_ONLY = new ReminderSettings(null, 2);

    private final ReminderSchedule schedule = new ReminderSchedule(new QuietHours(NIGHT, MOSCOW), MOSCOW);

    @Test
    void dayBeforeIsOnThePreviousSalonDayAtTheSetTime() {
        // Понедельник 10:00 → воскресенье 12:00; живёт до начала визита.
        assertThat(schedule.plan(null, MONDAY_10, DAY_BEFORE_ONLY, NOW))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-10-04T09:00:00Z"), MONDAY_10));
    }

    @Test
    void dayBeforeFollowsTheSalonDateNotTheUtcDate() {
        // Вторник 00:30 по Москве — ещё понедельник по UTC; накануне — понедельник 12:00.
        var tuesdayNight = Instant.parse("2026-10-05T21:30:00Z");

        assertThat(schedule.plan(null, tuesdayNight, DAY_BEFORE_ONLY, NOW))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-10-05T09:00:00Z"), tuesdayNight));
    }

    @Test
    void hoursBeforeIsThatManyHoursBeforeTheVisit() {
        // Понедельник 14:00 → 12:00.
        var monday14 = Instant.parse("2026-10-05T11:00:00Z");

        assertThat(schedule.plan(null, monday14, TWO_HOURS_ONLY, NOW))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_HOURS_BEFORE, Instant.parse("2026-10-05T09:00:00Z"), monday14));
    }

    @Test
    void hoursBeforeInQuietHoursMovesToTheirEnd() {
        // Визит 9:30, за 2 часа — 7:30, тихие часы → 9:00.
        var monday0930 = Instant.parse("2026-10-05T06:30:00Z");

        assertThat(schedule.plan(null, monday0930, TWO_HOURS_ONLY, NOW))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_HOURS_BEFORE, Instant.parse("2026-10-05T06:00:00Z"), monday0930));
    }

    @Test
    void hoursBeforeMovedToTheVisitStartIsSkipped() {
        // Визит 9:00, за 2 часа — 7:00, тихие часы → 9:00, не раньше начала визита.
        var monday09 = Instant.parse("2026-10-05T06:00:00Z");

        assertThat(schedule.plan(null, monday09, TWO_HOURS_ONLY, NOW)).isEmpty();
    }

    @Test
    void bothRemindersWithDefaultSettings() {
        // Визит 10:00: накануне 12:00 и за 2 часа — 8:00 → 9:00.
        assertThat(schedule.plan(null, MONDAY_10, new ReminderSettings(LocalTime.NOON, 2), NOW))
                .containsExactly(
                        new Reminder(MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-10-04T09:00:00Z"), MONDAY_10),
                        new Reminder(
                                MessageKind.REMINDER_HOURS_BEFORE, Instant.parse("2026-10-05T06:00:00Z"), MONDAY_10));
    }

    @Test
    void reminderThatIsAlreadyDueIsSkipped() {
        var settings = new ReminderSettings(LocalTime.NOON, 2);
        // Сейчас воскресенье 12:00 — ровно срок напоминания накануне: оно уже не нужно, за 2 часа — ещё впереди.
        var sundayNoon = Instant.parse("2026-10-04T09:00:00Z");

        assertThat(schedule.plan(null, MONDAY_10, settings, sundayNoon))
                .extracting(Reminder::kind)
                .containsExactly(MessageKind.REMINDER_HOURS_BEFORE);
        // Минутой раньше — оба.
        assertThat(schedule.plan(null, MONDAY_10, settings, sundayNoon.minusSeconds(60)))
                .extracting(Reminder::kind)
                .containsExactly(MessageKind.REMINDER_DAY_BEFORE, MessageKind.REMINDER_HOURS_BEFORE);
        // Визит завтра в 10:00, а сейчас 13:00 — накануне уже прошло.
        assertThat(schedule.plan(null, MONDAY_10, settings, Instant.parse("2026-10-04T10:00:00Z")))
                .extracting(Reminder::kind)
                .containsExactly(MessageKind.REMINDER_HOURS_BEFORE);
        // Визит уже начался — ничего.
        assertThat(schedule.plan(null, MONDAY_10, settings, MONDAY_10)).isEmpty();
    }

    @Test
    void acceptedEarlierTimeIsTheVisitStart() {
        // Слот 14:00, согласовано 11:00: за 2 часа — 9:00, живёт до 11:00.
        var slot = Instant.parse("2026-10-05T11:00:00Z");
        var agreed = Instant.parse("2026-10-05T08:00:00Z");

        assertThat(schedule.plan(agreed, slot, new ReminderSettings(LocalTime.NOON, 2), NOW))
                .containsExactly(
                        new Reminder(MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-10-04T09:00:00Z"), agreed),
                        new Reminder(MessageKind.REMINDER_HOURS_BEFORE, Instant.parse("2026-10-05T06:00:00Z"), agreed));
    }

    @Test
    void disabledRemindersAreNotPlanned() {
        assertThat(schedule.plan(null, MONDAY_10, new ReminderSettings(null, null), NOW))
                .isEmpty();
        assertThat(schedule.plan(null, MONDAY_10, DAY_BEFORE_ONLY, NOW))
                .extracting(Reminder::kind)
                .containsExactly(MessageKind.REMINDER_DAY_BEFORE);
        assertThat(schedule.plan(null, MONDAY_10, TWO_HOURS_ONLY, NOW))
                .extracting(Reminder::kind)
                .containsExactly(MessageKind.REMINDER_HOURS_BEFORE);
    }

    @Test
    void daylightSavingTransitionsFollowTheSalonZone() {
        // Берлин без тихих часов: 29 марта 2026 в 2:00 CET часы переводятся на 3:00 CEST.
        var berlin = ZoneId.of("Europe/Berlin");
        var noQuietHours = new QuietHours(new QuietHoursWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT), berlin);
        var inBerlin = new ReminderSchedule(noQuietHours, berlin);
        var march = Instant.parse("2026-03-01T00:00:00Z");

        // Визит в воскресенье 29 марта, 12:00 CEST: за 12 часов — это 12 настоящих часов, суббота 23:00 CET, а не
        // полночь по часам на стене.
        var sundayNoon = Instant.parse("2026-03-29T10:00:00Z");
        assertThat(inBerlin.plan(null, sundayNoon, new ReminderSettings(null, 12), march))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_HOURS_BEFORE, Instant.parse("2026-03-28T22:00:00Z"), sundayNoon));

        // Визит в понедельник 30 марта, 10:00 CEST, накануне в 12:00 — воскресенье 12:00 CEST (летнее время).
        var mondayMorning = Instant.parse("2026-03-30T08:00:00Z");
        assertThat(inBerlin.plan(null, mondayMorning, new ReminderSettings(LocalTime.NOON, null), march))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-03-29T10:00:00Z"), mondayMorning));

        // Накануне в 2:30 — такого времени 29 марта нет: напоминание сдвигается на длину перевода, на 3:30 CEST.
        assertThat(inBerlin.plan(null, mondayMorning, new ReminderSettings(LocalTime.of(2, 30), null), march))
                .containsExactly(new Reminder(
                        MessageKind.REMINDER_DAY_BEFORE, Instant.parse("2026-03-29T01:30:00Z"), mondayMorning));
    }
}
