package gag.nails.finance.model;

/** Вид операции в журнале операций. */
public enum JournalType {
    /** Выполненный визит: доход — итоговая сумма визита. */
    VISIT,
    /** Удержанная предоплата отменённой записи или неявки. */
    KEPT_PREPAYMENT,
    /** Ручной доход. */
    INCOME,
    /** Ручной расход. */
    EXPENSE
}
