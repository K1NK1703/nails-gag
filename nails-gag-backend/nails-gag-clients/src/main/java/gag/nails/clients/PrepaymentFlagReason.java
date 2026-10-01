package gag.nails.clients;

/** Почему признак обязательной предоплаты поставлен или снят. */
public enum PrepaymentFlagReason {
    LATE_CANCELLATION,
    NO_SHOW,
    LATE_ARRIVAL,
    VISIT_WITH_PREPAYMENT_COMPLETED,
    MASTER_SET,
    MASTER_WAIVED,
    /** Объединение клиентов: признак переходит, если был хотя бы у одного. */
    MERGED
}
