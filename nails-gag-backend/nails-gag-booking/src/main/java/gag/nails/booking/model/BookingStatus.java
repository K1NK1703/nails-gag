package gag.nails.booking.model;

import java.util.Set;

/** Состояние записи. Активная запись держит слот занятым и может быть отменена или перенесена. */
public enum BookingStatus {
    /** Создана с требованием предоплаты и ждёт её до срока. */
    AWAITING_PREPAYMENT,
    CONFIRMED,
    /** Визит состоялся. */
    COMPLETED,
    /** Клиент не пришёл. */
    NO_SHOW,
    /** Отменена; слот освобождён. Переходов из этого состояния нет. */
    CANCELLED;

    public boolean isActive() {
        return this == AWAITING_PREPAYMENT || this == CONFIRMED;
    }

    public static Set<BookingStatus> active() {
        return Set.of(AWAITING_PREPAYMENT, CONFIRMED);
    }
}
