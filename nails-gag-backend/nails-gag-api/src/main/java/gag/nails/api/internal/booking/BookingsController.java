package gag.nails.api.internal.booking;

import gag.nails.api.internal.http.MasterNote;
import gag.nails.api.internal.http.PeriodParams;
import gag.nails.api.internal.http.SetMasterNote;
import gag.nails.booking.BookingQueries;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Записи (спецификация API, §7.6): чтение и очереди, создание, отмена, отказ опоздавшему, перенос, комментарий и
 * заметка мастера; остальные переходы — в {@link BookingActionsController}. Сотруднику — по уровню раздела «Записи»,
 * клиенту — свои записи; права и правила записи проверяет модуль. Чужая запись клиенту — как несуществующая,
 * {@code 404 common.not_found}; конфликт с состоянием записи или правилами — {@code 409}. Даты — в поясе салона,
 * период {@code from}/{@code to} — включительно и не длиннее {@value PeriodParams#MAX_DAYS} дней.
 */
@RestController
@RequestMapping("/api/bookings")
class BookingsController {

    private final BookingQueries queries;
    private final BookingService bookings;

    BookingsController(BookingQueries queries, BookingService bookings) {
        this.queries = queries;
        this.bookings = bookings;
    }

    /** Все записи периода по времени начала, включая отменённые; нужен уровень чтения раздела «Записи». */
    @GetMapping
    List<BookingView> ofPeriod(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        PeriodParams.check(from, to);
        return queries.ofPeriod(from, to, actor);
    }

    /**
     * Активные записи, слот которых уже начался: пора отметить визит. Нужен уровень чтения раздела «Записи».
     */
    @GetMapping("/awaiting-mark")
    List<BookingView> awaitingMark(Actor actor) {
        return queries.awaitingMark(actor);
    }

    /** Записи, ждущие предоплату, по сроку; нужен уровень чтения раздела «Записи». */
    @GetMapping("/awaiting-prepayment")
    List<BookingView> awaitingPrepayment(Actor actor) {
        return queries.awaitingPrepayment(actor);
    }

    /** Запись без заметки мастера; сотруднику с чтением «Записей» — любая, клиенту — своя. */
    @GetMapping("/{id}")
    BookingView get(@PathVariable UUID id, Actor actor) {
        return queries.get(id, actor);
    }

    /**
     * Клиент записывается сам: {@code clientId} — своя карточка, иначе клиенту — {@code 404 common.not_found},
     * сотруднику — {@code 403 common.access_denied}. Модуль проверяет лимит активных записей, согласия на действующие
     * документы и отсечку перед слотом ({@code 409}); заблокированный или обезличенный клиент — {@code 403}; занятый
     * слот или скрытая услуга — {@code 409}; неверный состав — {@code 400}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BookingView createByClient(@Valid @RequestBody CreateBooking body, Actor actor) {
        return bookings.createByClient(body, actor);
    }

    /**
     * Сотрудник с изменением раздела «Записи» записывает клиента без лимита, согласий и отсечки, в том числе
     * заблокированного. Обезличенная карточка — {@code 403}; занятый слот или скрытая услуга — {@code 409}; неверный
     * состав, слот старше срока исправления отметок или предоплата для прошедшего слота — {@code 400}.
     */
    @PostMapping("/by-master")
    @ResponseStatus(HttpStatus.CREATED)
    BookingView createByMaster(@Valid @RequestBody CreateBookingByMaster body, Actor actor) {
        return bookings.createByMaster(body.toCommand(), body.awaitPrepayment(), actor);
    }

    /**
     * Отмена активной записи. Клиент отменяет свою до начала слота: в окне бесплатной отмены — без последствий, позже
     * — с признаком обязательной предоплаты. Сотрудник с изменением «Записей» — любую и в любое время. Неактивная
     * запись или отмена клиентом после начала слота — {@code 409}.
     */
    @PostMapping("/{id}/cancel")
    BookingView cancel(@PathVariable UUID id, Actor actor) {
        return bookings.cancel(id, actor);
    }

    /**
     * Отказ опоздавшему клиенту после начала слота, с признаком обязательной предоплаты; нужен уровень изменения
     * раздела «Записи». Слот ещё не начался или запись не активна — {@code 409}.
     */
    @PostMapping("/{id}/refuse-late-arrival")
    BookingView refuseLateArrival(@PathVariable UUID id, Actor actor) {
        return bookings.refuseLateArrival(id, actor);
    }

    /**
     * Перенос активной записи на другой свободный слот. Клиент переносит свою только в окне бесплатной отмены и на
     * слот не ближе отсечки ({@code 409}), заблокированный — никак ({@code 403}); сотрудник с изменением «Записей» —
     * любую, но не на слот старше срока исправления отметок и не запись, ждущую предоплату, — на уже начавшийся слот
     * ({@code 400}). Занятый слот или неактивная запись — {@code 409}; тот же слот — {@code 400}.
     */
    @PostMapping("/{id}/reschedule")
    BookingView reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleBooking body, Actor actor) {
        return bookings.reschedule(id, body.newSlotId(), actor);
    }

    /**
     * Комментарий клиента к активной записи; клиент правит свою, сотрудник с изменением «Записей» — любую. Неактивная
     * запись — {@code 409}.
     */
    @PutMapping("/{id}/comment")
    BookingView updateComment(@PathVariable UUID id, @Valid @RequestBody SetComment body, Actor actor) {
        return bookings.updateComment(id, body.comment(), actor);
    }

    /** Заметка мастера к записи; клиенту не видна. Нужен уровень чтения раздела «Записи». */
    @GetMapping("/{id}/note")
    MasterNote masterNote(@PathVariable UUID id, Actor actor) {
        return new MasterNote(queries.masterNote(id, actor).orElse(null));
    }

    /**
     * Заметка мастера целиком, в любом состоянии записи; нужен уровень изменения раздела «Записи». Пустая строка
     * стирает заметку.
     */
    @PutMapping("/{id}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateMasterNote(@PathVariable UUID id, @Valid @RequestBody SetMasterNote body, Actor actor) {
        bookings.updateMasterNote(id, body.note(), actor);
    }
}
