package gag.nails.booking;

import gag.nails.booking.command.UpdateActualServices;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.MarkResult;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import java.util.List;
import java.util.UUID;

/**
 * Отметки визита; нужен уровень изменения раздела «Записи». Первая отметка ставится активной
 * записи после начала слота; ошибочную отметку можно сменить на противоположную в пределах срока
 * исправления, считая от начала слота. Слот прошедшего визита остаётся занятым.
 */
public interface VisitMarks {

    /** Сколько идентификаторов (вместе с повторами) принимает один вызов {@link #completeAsPlanned}. */
    int MAX_BATCH = 50;

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
     * Пакетная отметка «все выполнены по прайсу» для списка ждущих отметки: каждая запись отмечается как
     * {@link #complete} в своей транзакции, поэтому сбой одной не мешает остальным. Повторы идентификаторов
     * убираются, результаты — в порядке первого появления. Уровень изменения «Записей» проверяется один раз до
     * отметок. Вызывать вне транзакции.
     *
     * @param bookingIds не больше {@value #MAX_BATCH} идентификаторов, считая повторы; пустой список — пустой итог
     * @return по результату на каждую различную запись: пустой код — отмечена, иначе код ошибки, как у
     *     {@link #complete} ({@code booking.booking_not_found}, {@code booking.visit_not_started}, {@code
     *     booking.invalid_transition}, {@code booking.correction_window_closed}); запись занята другой
     *     операцией и блокировку взять не удалось — {@code common.busy}; непредвиденный сбой — {@code
     *     common.internal}
     * @throws ValidationException если идентификаторов больше {@value #MAX_BATCH} или есть пустой
     *     ({@code bookingIds})
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     * @throws InternalException если вызван внутри транзакции
     * @throws org.springframework.dao.DataAccessException если база недоступна или транзакцию не начать: пакет
     *     прерывается, уже отмеченные записи остаются отмеченными
     */
    List<MarkResult> completeAsPlanned(List<UUID> bookingIds, Actor actor);

    /**
     * Клиент не пришёл: клиенту ставится признак обязательной предоплаты, если он ещё не стоит;
     * фактический состав, итог и причина суммы, если были, стираются. Предложение приехать раньше,
     * ждавшее ответа, истекает.
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
     * Названия и цены берутся из каталога на момент правки; скрытые услуги допустимы. Причину суммы
     * не меняет ({@link #updateVisit}).
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если визит не отмечен выполненным
     * @throws CorrectionWindowClosedException если срок исправления истёк
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ValidationException если состав неверен или сумма отрицательна
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView updateActualServices(UUID bookingId, UpdateActualServices command, Actor actor);

    /**
     * Как {@link #updateActualServices}, плюс причина итоговой суммы: итог не меньше нуля, больше прайса —
     * допустим; причина — до 200 символов, пустая стирает.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если визит не отмечен выполненным
     * @throws CorrectionWindowClosedException если срок исправления истёк
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ValidationException если состав неверен, сумма отрицательна или в ней больше десяти цифр до запятой
     *     и двух после, либо причина длиннее 200 символов
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView updateVisit(UUID bookingId, UpdateVisit command, Actor actor);
}
