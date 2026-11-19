package gag.nails.notifications.model;

/** Состояние доставки сообщения по одному каналу. */
public enum DeliveryStatus {
    /** Ждёт попытки отправки (первой или повторной). */
    PENDING,
    /** Отправлено. */
    SENT,
    /** Не отправлено и больше не пробуем; причина — в коде ошибки. */
    FAILED
}
