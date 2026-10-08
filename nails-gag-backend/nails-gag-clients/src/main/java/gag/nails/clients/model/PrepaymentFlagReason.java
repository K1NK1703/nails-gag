package gag.nails.clients.model;

/** Почему признак обязательной предоплаты поставлен или снят. */
public enum PrepaymentFlagReason {
    LATE_CANCELLATION,
    NO_SHOW,
    LATE_ARRIVAL,
    VISIT_WITH_PREPAYMENT_COMPLETED,
    /** Отметка «не пришла» исправлена на «выполнена»: признак, поставленный за неявку, снимается. */
    NO_SHOW_CORRECTED,
    MASTER_SET,
    MASTER_WAIVED,
    /** Объединение клиентов: признак переходит, если был хотя бы у одного. */
    MERGED
}
