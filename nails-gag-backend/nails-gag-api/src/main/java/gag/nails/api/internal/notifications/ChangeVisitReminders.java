package gag.nails.api.internal.notifications;

import gag.nails.common.error.ValidationException;
import gag.nails.notifications.command.UpdateReminderSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/notifications/settings}: команда {@link UpdateReminderSettings} в именах тела HTTP (спецификация
 * уведомлений, §4). Обе настройки сразу; {@code null} — напоминание выключено. Пределы проверяет модуль, здесь они
 * только в схеме для фронта; его нарушение называет поле этого тела ({@link #inBodyTerms}).
 *
 * @param reminderDayBeforeAt время накануне визита {@code "ЧЧ:ММ"}: в целых минутах и вне тихих часов
 *     ({@code nails-gag.notifications.quiet-hours})
 * @param reminderHoursBefore за сколько часов до визита, от 1 до 24
 */
record ChangeVisitReminders(
        @Schema(example = "12:00") @Nullable LocalTime reminderDayBeforeAt,
        @Schema(minimum = "1", maximum = "24") @Nullable Integer reminderHoursBefore) {

    UpdateReminderSettings toCommand() {
        return new UpdateReminderSettings(reminderDayBeforeAt, reminderHoursBefore);
    }

    /**
     * Нарушение из модуля с именем поля этого тела: модуль называет поля своей команды ({@code dayBeforeAt},
     * {@code hoursBefore}), а клиент прислал {@code reminderDayBeforeAt} и {@code reminderHoursBefore}. Поле, которого
     * в теле нет, остаётся как есть.
     */
    static ValidationException inBodyTerms(ValidationException exception) {
        var field = switch (exception.field()) {
            case "dayBeforeAt" -> "reminderDayBeforeAt";
            case "hoursBefore" -> "reminderHoursBefore";
            default -> exception.field();
        };
        return new ValidationException(field, exception.reason());
    }
}
