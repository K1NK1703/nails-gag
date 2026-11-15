package gag.nails.notifications.model;

/** Состояние логического сообщения в очереди. */
public enum MessageStatus {
    /** Ждёт своего времени отправки. */
    PENDING,
    /** Разобрано по каналам: доставки созданы (или каналов нет). */
    DISPATCHED,
    /** Отменено: стало неактуальным до отправки. */
    CANCELLED
}
