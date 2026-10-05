package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LateRescheduleNotAllowedException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Записи: создание, отмена, перенос. Каждая операция — одна транзакция: занятие слота, запись и
 * изменение признака предоплаты у клиента либо происходят вместе, либо не происходят вовсе.
 */
public interface BookingService {

    /**
     * Клиент записывается сам. Запись подтверждается сразу; если у клиента признак обязательной
     * предоплаты — ждёт её до срока. Проверяются лимит активных записей, согласия на действующие
     * политику и оферту и отсечка перед началом слота.
     *
     * @throws LimitReachedException если у клиента уже предельное число активных записей
     * @throws ConsentMissingException если нет согласий на действующие редакции документов
     * @throws TooLateToBookException если до начала слота меньше отсечки
     * @throws SlotOccupiedException если слот уже занят
     * @throws SlotNotFoundException если слота нет
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ServiceInactiveException если какая-то услуга скрыта
     * @throws ValidationException если состав неверен: не основная услуга, не дополнение или повтор
     * @throws AccessDeniedException если клиент записывает не себя
     */
    BookingView createByClient(CreateBooking command, Actor actor);

    /**
     * Мастер записывает клиента: без лимита, согласий и отсечки. Слот может быть в прошлом в пределах
     * срока исправления отметок — так вносится визит, прошедший без записи. {@code awaitPrepayment}
     * решает, ждёт ли запись предоплату; признак клиента при этом не меняется.
     *
     * @throws ClientNotFoundException если клиента нет
     * @throws SlotOccupiedException если слот уже занят
     * @throws SlotNotFoundException если слота нет
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ServiceInactiveException если какая-то услуга скрыта
     * @throws ValidationException если состав неверен, слот старше срока исправления или предоплата
     *     запрошена для слота в прошлом
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView createByMaster(CreateBooking command, boolean awaitPrepayment, Actor actor);

    /**
     * Отменяет активную запись и освобождает слот. Клиент отменяет свою запись: в окне бесплатной
     * отмены — без последствий, позже — это поздняя отмена, и клиенту ставится признак обязательной
     * предоплаты; после начала слота клиент отменить запись уже не может. Мастер отменяет любую
     * запись в любое время без последствий для клиента.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не активна или клиент отменяет после начала слота
     * @throws AccessDeniedException если клиент отменяет чужую запись
     */
    BookingView cancel(UUID bookingId, Actor actor);

    /**
     * Мастер отказывает клиенту, опоздавшему больше допустимого: запись отменяется после начала
     * слота, клиенту ставится признак обязательной предоплаты, как при поздней отмене.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не активна
     * @throws VisitNotStartedException если слот ещё не начался
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView refuseLateArrival(UUID bookingId, Actor actor);

    /**
     * Переносит активную запись на другой свободный слот. Состояние сохраняется; согласованное более
     * раннее время сбрасывается, ждущее ответа предложение приехать раньше отзывается; срок
     * предоплаты не продлевается, но укладывается в отсечку перед новым слотом. Клиент переносит
     * свою запись только в окне бесплатной отмены и только на слот не ближе отсечки, а запись с
     * истёкшим сроком предоплаты не переносит вовсе; мастер — любую запись в любое время, но на слот
     * не старше срока исправления отметок, а запись, ждущую предоплату, — только на ещё не начавшийся.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не активна, либо клиент переносит после начала
     *     слота или с истёкшим сроком предоплаты
     * @throws LateRescheduleNotAllowedException если клиент переносит позже окна бесплатной отмены
     * @throws TooLateToBookException если клиент переносит на слот ближе отсечки
     * @throws SlotOccupiedException если новый слот занят
     * @throws SlotNotFoundException если нового слота нет
     * @throws ValidationException если новый слот совпадает с текущим, либо мастер переносит на слот
     *     старше срока исправления или запись, ждущую предоплату, — на уже начавшийся слот
     * @throws AccessDeniedException если клиент переносит чужую запись
     */
    BookingView reschedule(UUID bookingId, UUID newSlotId, Actor actor);

    /**
     * Комментарий клиента к активной записи, до 500 символов; пустой стирает. Клиент правит свою
     * запись, мастер — любую.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не активна
     * @throws ValidationException если комментарий длиннее 500 символов
     * @throws AccessDeniedException если клиент правит чужую запись
     */
    BookingView updateComment(UUID bookingId, @Nullable String comment, Actor actor);

    /**
     * Заметка мастера к записи в любом состоянии, до 2000 символов; пустая стирает. Клиенту не видна.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws ValidationException если заметка длиннее 2000 символов
     * @throws AccessDeniedException если действует не мастер
     */
    void updateMasterNote(UUID bookingId, String note, Actor actor);
}
