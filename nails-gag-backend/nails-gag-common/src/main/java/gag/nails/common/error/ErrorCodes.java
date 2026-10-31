package gag.nails.common.error;

/**
 * Общие коды ошибок, у которых нет своего класса исключения: их отдаёт слой HTTP, и они же попадают в итоги
 * пакетных операций модулей (например, пакетной отметки визитов), чтобы фронт понимал их одинаково.
 */
public final class ErrorCodes {

    /** Непредвиденный сбой; подробности — только в логе. */
    public static final String INTERNAL = "common.internal";

    /** База занята (не удалось взять блокировку); действие можно повторить. */
    public static final String BUSY = "common.busy";

    private ErrorCodes() {}
}
