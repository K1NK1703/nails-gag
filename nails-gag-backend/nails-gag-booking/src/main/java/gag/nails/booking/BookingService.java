package gag.nails.booking;

import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;

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
}
