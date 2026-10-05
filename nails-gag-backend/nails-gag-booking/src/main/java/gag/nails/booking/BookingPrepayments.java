package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.util.UUID;

/**
 * Предоплата по записи. Сайт денег не принимает и реквизитов не показывает: предоплата вносится вне
 * сайта, мастер отмечает её получение. Обе операции — только мастер и только для записи, ждущей
 * предоплату.
 */
public interface BookingPrepayments {

    /**
     * Предоплата получена: запись подтверждена. Признак обязательной предоплаты у клиента снимется
     * после выполненного визита.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView markReceived(UUID bookingId, Actor actor);

    /**
     * Мастер подтверждает запись без предоплаты и снимает с клиента признак обязательной предоплаты.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor);
}
