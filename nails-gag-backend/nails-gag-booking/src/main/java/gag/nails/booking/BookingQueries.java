package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение записей. Сотрудник с уровнем чтения раздела «Записи» видит все, клиент — только свои.
 * Даты — в поясе салона.
 */
public interface BookingQueries {

    /**
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если актор без чтения «Записей» смотрит чужую запись
     */
    BookingView get(UUID bookingId, Actor actor);

    /**
     * Активные записи клиента, ближайшие первыми.
     *
     * @throws AccessDeniedException если актор без чтения «Записей» смотрит чужие записи
     */
    List<BookingView> activeOfClient(UUID clientId, Actor actor);

    /**
     * Завершённые и отменённые записи клиента, последние первыми.
     *
     * @throws AccessDeniedException если актор без чтения «Записей» смотрит чужие записи
     */
    List<BookingView> historyOfClient(UUID clientId, Actor actor);

    /**
     * Все записи дня по времени начала, включая отменённые. Нужен уровень чтения раздела
     * «Записи».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Записи»
     */
    List<BookingView> ofDay(LocalDate day, Actor actor);

    /**
     * Все записи периода (обе границы включительно) по времени начала. Нужен уровень чтения
     * раздела «Записи».
     *
     * @throws ValidationException если {@code to} раньше {@code from}
     * @throws AccessDeniedException если у актора нет чтения раздела «Записи»
     */
    List<BookingView> ofPeriod(LocalDate from, LocalDate to, Actor actor);

    /**
     * Активные записи, слот которых уже начался: мастеру пора отметить «выполнена» или «не пришла».
     * Нужен уровень чтения раздела «Записи».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Записи»
     */
    List<BookingView> awaitingMark(Actor actor);

    /**
     * Записи, ждущие предоплату, по сроку. Нужен уровень чтения раздела «Записи».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Записи»
     */
    List<BookingView> awaitingPrepayment(Actor actor);

    /**
     * Заметка мастера к записи; клиенту не видна. Нужен уровень чтения раздела «Записи».
     *
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если у актора нет чтения раздела «Записи»
     */
    Optional<String> masterNote(UUID bookingId, Actor actor);
}
