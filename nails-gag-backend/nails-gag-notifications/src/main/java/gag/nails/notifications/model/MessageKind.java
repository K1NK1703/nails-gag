package gag.nails.notifications.model;

/** О чём сообщение. Виды без префикса — клиентке (карточке), {@code STAFF_*} — сотрудникам (аккаунтам). */
public enum MessageKind {
    /** Клиентка записалась, запись подтверждена сразу. */
    BOOKING_RECEIVED,
    /** Мастер записал клиентку, запись подтверждена сразу. */
    BOOKED_BY_MASTER,
    /** По записи ждут предоплату: сумма и срок. */
    PREPAYMENT_REQUESTED,
    /** Запись, ждавшая предоплату, подтверждена. */
    CONFIRMED,
    /** Клиентка отменила запись. */
    CANCELLED_BY_CLIENT,
    /** Запись отменена не клиенткой; причина — в тексте. */
    CANCELLED,
    /** Запись перенесена. */
    RESCHEDULED,
    /** Мастер предлагает приехать раньше. */
    EARLIER_PROPOSED,
    /** Напоминание накануне визита. */
    REMINDER_DAY_BEFORE,
    /** Напоминание за несколько часов до визита. */
    REMINDER_HOURS_BEFORE,
    /** Клиентка записалась. */
    STAFF_NEW_BOOKING,
    /** Клиентка отменила запись. */
    STAFF_CLIENT_CANCELLED,
    /** Запись отменена: предоплата не пришла в срок. */
    STAFF_PREPAYMENT_TIMEOUT,
    /** Клиентка перенесла запись. */
    STAFF_CLIENT_RESCHEDULED,
    /** Клиентка согласилась приехать раньше. */
    STAFF_EARLIER_ACCEPTED,
    /** Клиентка отказалась приехать раньше. */
    STAFF_EARLIER_DECLINED
}
