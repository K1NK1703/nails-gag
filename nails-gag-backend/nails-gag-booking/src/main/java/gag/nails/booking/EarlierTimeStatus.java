package gag.nails.booking;

/** Судьба предложения приехать раньше. */
public enum EarlierTimeStatus {
    /** Ждёт ответа клиента. */
    PENDING,
    ACCEPTED,
    DECLINED,
    /** Клиент не ответил до начала слота. */
    EXPIRED,
    /** Мастер отозвал, либо запись перенесли или отменили. */
    WITHDRAWN
}
