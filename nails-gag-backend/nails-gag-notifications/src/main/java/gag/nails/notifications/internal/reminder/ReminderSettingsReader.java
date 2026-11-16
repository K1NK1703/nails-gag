package gag.nails.notifications.internal.reminder;

import gag.nails.notifications.model.ReminderSettings;

/**
 * Настройки напоминаний для планировщика и для всех, кто меняет неотправленные сообщения: строка настроек — первая в
 * порядке блокировок очереди ({@link ReminderPlanner}). Реализация — у настроек; интерфейс здесь, чтобы планировщик
 * не зависел от пакета настроек, который сам вызывает планировщик при смене настроек.
 */
public interface ReminderSettingsReader {

    /**
     * Текущие настройки под разделяемой блокировкой строки ({@code FOR SHARE}) до конца транзакции: смена настроек
     * ({@code FOR UPDATE}) ждёт её фиксации, а идущую смену ждёт она сама. Только внутри транзакции.
     */
    ReminderSettings readForShare();
}
