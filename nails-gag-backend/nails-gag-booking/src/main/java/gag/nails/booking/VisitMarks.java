package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.UUID;

/**
 * Отметки визита; нужен уровень изменения раздела «Записи». Первая отметка ставится активной
 * записи после начала слота; ошибочную отметку можно сменить на противоположную в пределах срока
 * исправления, считая от начала слота. Слот прошедшего визита остаётся занятым.
 */
public interface VisitMarks {

    /**
     * Визит выполнен. Фактический состав копируется из выбранного при записи, итог — сумма его цен;
     * мастер правит их через {@link #updateActualServices}. Если предоплата по записи была получена,
     * с клиента снимается признак обязательной предоплаты. Исправление отметки «не пришла» снимает
     * признак, только если он стоит именно за эту неявку; поставленный по другой причине (до неявки
     * или после неё) остаётся. Предложение приехать раньше, ждавшее ответа, истекает.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws VisitNotStartedException если слот активной записи ещё не начался
     * @throws CorrectionWindowClosedException если срок исправления отметки истёк
     * @throws InvalidTransitionException если запись отменена или уже отмечена выполненной
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView complete(UUID bookingId, Actor actor);

    /**
     * Клиент не пришёл: клиенту ставится признак обязательной предоплаты, если он ещё не стоит;
     * фактический состав и итог, если были, стираются. Предложение приехать раньше, ждавшее ответа,
     * истекает.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws VisitNotStartedException если слот активной записи ещё не начался
     * @throws CorrectionWindowClosedException если срок исправления отметки истёк
     * @throws InvalidTransitionException если запись отменена или уже отмечена неявкой
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView markNoShow(UUID bookingId, Actor actor);

    /**
     * Фактический состав и итоговая сумма выполненного визита, в пределах срока исправления.
     * Названия и цены берутся из каталога на момент правки; скрытые услуги допустимы.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если визит не отмечен выполненным
     * @throws CorrectionWindowClosedException если срок исправления истёк
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ValidationException если состав неверен или сумма отрицательна
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView updateActualServices(UUID bookingId, UpdateActualServices command, Actor actor);
}
