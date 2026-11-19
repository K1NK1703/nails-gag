package gag.nails.api.internal.notifications;

import com.fasterxml.jackson.annotation.JsonProperty;
import gag.nails.common.error.ValidationException;
import gag.nails.notifications.command.UpdateReminderSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/notifications/settings}: команда {@link UpdateReminderSettings} в именах тела HTTP (спецификация
 * уведомлений, §4). Обе настройки сразу, оба ключа обязательны ({@link JsonProperty#required()}): напоминание
 * выключает только явный {@code null}, а тело без ключа — {@code 400 common.validation} с его именем. Иначе
 * пропущенный ключ (опечатка, старый клиент) молча выключал бы напоминание всем клиенткам: Jackson подставил бы
 * {@code null}.
 *
 * <p>Пределы часов повторены аннотациями, чтобы {@code @Valid} сообщал нарушения сразу все и в именах этого тела, а
 * схема OpenAPI показывала их фронту; время накануне (целые минуты, вне тихих часов) проверяет только модуль.
 * Нарушение из модуля называет поле этого тела ({@link #inBodyTerms}).
 *
 * @param reminderDayBeforeAt время накануне визита {@code "ЧЧ:ММ"}: в целых минутах и вне тихих часов
 *     ({@code nails-gag.notifications.quiet-hours})
 * @param reminderHoursBefore за сколько часов до визита, от 1 до 24
 */
record ChangeVisitReminders(
        @JsonProperty(required = true) @Schema(example = "12:00") @Nullable
        LocalTime reminderDayBeforeAt,

        @JsonProperty(required = true) @Min(1) @Max(24) @Nullable
        Integer reminderHoursBefore) {

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
