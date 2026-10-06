package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.util.UUID;

/**
 * Предоплата по записи. Сайт денег не принимает и реквизитов не показывает: предоплата вносится вне
 * сайта, сотрудник отмечает её получение. Обе операции требуют уровня изменения раздела «Записи» и
 * применимы только к записи, ждущей предоплату.
 */
public interface BookingPrepayments {

    /**
     * Предоплата получена: запись подтверждена. Признак обязательной предоплаты у клиента снимется
     * после выполненного визита.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView markReceived(UUID bookingId, Actor actor);

    /**
     * Подтверждает запись без предоплаты и снимает с клиента признак обязательной предоплаты. Признак
     * меняется от имени сотрудника, поэтому кроме изменения «Записей» нужен уровень изменения раздела
     * «Клиенты»; оба проверяются до изменений.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если у актора нет изменения «Записей» или «Клиентов»
     */
    BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor);
}
