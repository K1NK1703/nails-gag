package gag.nails.notifications.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * Новые настройки напоминаний целиком; в журнал попадают только изменившиеся. Пусто — напоминание выключено.
 *
 * @param dayBeforeAt время накануне визита, в целых минутах и вне тихих часов
 *     ({@code nails-gag.notifications.quiet-hours})
 * @param hoursBefore за сколько часов до визита, от 1 до 24
 */
public record UpdateReminderSettings(
        @Nullable LocalTime dayBeforeAt,

        @Min(1) @Max(24) @Nullable Integer hoursBefore) {}
