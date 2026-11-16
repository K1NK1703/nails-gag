package gag.nails.notifications.internal.reminder;

import gag.nails.booking.model.BookingView;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.ReminderSettings;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Расчёт напоминаний о визите (спека §3.4) — чистые функции без часов и базы. Начало визита — согласованное более
 * раннее время, если клиентка приняла «пораньше», иначе начало слота; дата визита — по поясу салона.
 *
 * <ul>
 *   <li>Накануне — в {@code dayBeforeAt} по времени салона в день перед датой визита. Время накануне при смене
 *       настроек проверено вне тихих часов, поэтому не переносится; время, которого в этот день нет (перевод часов
 *       вперёд), сдвигается на длину перевода.
 *   <li>За N часов — за N настоящих часов до начала визита, с переносом из тихих часов на их конец.
 * </ul>
 *
 * Оба живут до начала визита. Напоминание, срок которого уже наступил ({@code dueAt <= now}) или не раньше начала
 * визита (например, после переноса из тихих часов), не создаётся; выключенное в настройках ({@code null}) — тоже.
 */
public final class ReminderSchedule {

    private final QuietHours quietHours;
    private final ZoneId zone;

    /**
     * @param quietHours тихие часы клиенток
     * @param zone пояс салона
     */
    public ReminderSchedule(QuietHours quietHours, ZoneId zone) {
        this.quietHours = quietHours;
        this.zone = zone;
    }

    /**
     * Напоминания визита по настройкам на момент {@code now}: сначала накануне, затем за N часов.
     *
     * @param agreedStart согласованное более раннее начало; {@code null} — визит начинается в начале слота
     */
    public List<Reminder> plan(
            @Nullable Instant agreedStart, Instant slotStartsAt, ReminderSettings settings, Instant now) {
        var start = BookingView.visitStart(agreedStart, slotStartsAt);
        var reminders = new ArrayList<Reminder>(2);
        var dayBeforeAt = settings.dayBeforeAt();
        if (dayBeforeAt != null) {
            var visitDate = start.atZone(zone).toLocalDate();
            var dueAt = visitDate.minusDays(1).atTime(dayBeforeAt).atZone(zone).toInstant();
            add(reminders, MessageKind.REMINDER_DAY_BEFORE, dueAt, start, now);
        }
        var hoursBefore = settings.hoursBefore();
        if (hoursBefore != null) {
            var dueAt = quietHours.shift(start.minus(Duration.ofHours(hoursBefore)));
            add(reminders, MessageKind.REMINDER_HOURS_BEFORE, dueAt, start, now);
        }
        return List.copyOf(reminders);
    }

    private static void add(List<Reminder> reminders, MessageKind kind, Instant dueAt, Instant start, Instant now) {
        if (dueAt.isAfter(now) && dueAt.isBefore(start)) {
            reminders.add(new Reminder(kind, dueAt, start));
        }
    }

    /**
     * Напоминание к постановке в очередь.
     *
     * @param dueAt когда отправлять
     * @param expiresAt начало визита: позже напоминание не отправляется
     */
    public record Reminder(MessageKind kind, Instant dueAt, Instant expiresAt) {}
}
