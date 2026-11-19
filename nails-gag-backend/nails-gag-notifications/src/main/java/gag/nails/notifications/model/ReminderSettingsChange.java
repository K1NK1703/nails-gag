package gag.nails.notifications.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала настроек напоминаний: какое поле, что было, что стало, кто и когда.
 *
 * @param field имя колонки: {@code reminder_day_before_at} или {@code reminder_hours_before}
 * @param oldValue прежнее значение текстом ({@code 12:00}, {@code 2}); пусто — напоминание было выключено
 * @param newValue новое значение текстом; пусто — напоминание выключено
 * @param changedBy аккаунт, изменивший настройку; пусто у внутренних акторов
 */
public record ReminderSettingsChange(
        String field,
        @Nullable String oldValue,
        @Nullable String newValue,
        @Nullable UUID changedBy,
        Instant changedAt) {}
