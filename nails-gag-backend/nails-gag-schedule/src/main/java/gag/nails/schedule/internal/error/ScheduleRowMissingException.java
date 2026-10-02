package gag.nails.schedule.internal.error;

import gag.nails.common.error.InternalException;

/** Обязательной единственной строки (settings, rotation) нет в базе: не применён seed миграции. */
public final class ScheduleRowMissingException extends InternalException {

    public ScheduleRowMissingException(String table) {
        super("required single row is missing in " + table);
    }
}
