package gag.nails.api.internal.notifications;

import gag.nails.notifications.model.ReminderSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * Ответ {@code GET}/{@code PUT /api/notifications/settings}: настройки напоминаний {@link ReminderSettings} в именах
 * тела HTTP (спецификация уведомлений, §4) — поля названы так же, как в теле {@link ChangeVisitReminders}.
 *
 * @param reminderDayBeforeAt во сколько по времени салона напоминать накануне визита, {@code "12:00"}; {@code null} —
 *     не напоминать
 * @param reminderHoursBefore за сколько часов до начала визита напоминать, от 1 до 24; {@code null} — не напоминать
 */
record VisitReminders(
        @Schema(example = "12:00") @Nullable LocalTime reminderDayBeforeAt,
        @Nullable Integer reminderHoursBefore) {

    static VisitReminders of(ReminderSettings settings) {
        return new VisitReminders(settings.dayBeforeAt(), settings.hoursBefore());
    }
}
