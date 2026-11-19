package gag.nails.notifications.model;

import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * Настройки напоминаний о визите.
 *
 * @param dayBeforeAt во сколько по времени салона напоминать накануне визита; пусто — не напоминать
 * @param hoursBefore за сколько часов до начала визита напоминать, от 1 до 24; пусто — не напоминать
 */
public record ReminderSettings(
        @Nullable LocalTime dayBeforeAt, @Nullable Integer hoursBefore) {}
