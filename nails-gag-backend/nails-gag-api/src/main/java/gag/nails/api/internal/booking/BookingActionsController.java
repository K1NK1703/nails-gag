package gag.nails.api.internal.booking;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.command.UpdateActualServices;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Предоплата, предложение приехать раньше и отметки визита (спецификация API, §7.6). Всё, кроме ответа на
 * предложение, — по уровню изменения раздела «Записи»; ответ даёт клиент по своей записи. Права проверяет модуль;
 * переход, недопустимый в текущем состоянии записи, — {@code 409}. Ответ — запись после перехода.
 */
@RestController
@RequestMapping("/api/bookings/{id}")
class BookingActionsController {

    private final BookingPrepayments prepayments;
    private final EarlierTimeOffers earlierTime;
    private final VisitMarks marks;

    BookingActionsController(BookingPrepayments prepayments, EarlierTimeOffers earlierTime, VisitMarks marks) {
        this.prepayments = prepayments;
        this.earlierTime = earlierTime;
        this.marks = marks;
    }

    /** Предоплата получена вне сайта: запись подтверждена. Запись не ждёт предоплату — {@code 409}. */
    @PostMapping("/prepayment/received")
    BookingView markPrepaymentReceived(@PathVariable UUID id, Actor actor) {
        return prepayments.markReceived(id, actor);
    }

    /**
     * Подтверждение без предоплаты; с клиента снимается признак обязательной предоплаты, поэтому нужен ещё и уровень
     * изменения раздела «Клиенты». Запись не ждёт предоплату — {@code 409}.
     */
    @PostMapping("/prepayment/confirm-without")
    BookingView confirmWithoutPrepayment(@PathVariable UUID id, Actor actor) {
        return prepayments.confirmWithoutPrepayment(id, actor);
    }

    /**
     * Предложение клиенту подтверждённой записи приехать раньше; заменяет прежнее, на которое клиент уже ответил.
     * Запись не подтверждена или предложение уже ждёт ответа — {@code 409}; время вне дня слота или не между «сейчас»
     * и началом слота — {@code 400}.
     */
    @PostMapping("/earlier-time")
    BookingView proposeEarlierTime(@PathVariable UUID id, @Valid @RequestBody ProposeEarlierTime body, Actor actor) {
        return earlierTime.propose(id, body.proposedStart(), actor);
    }

    /**
     * Отзыв предложения, пока клиент не ответил; время, согласованное раньше, остаётся. Нет предложения, ждущего
     * ответа, — {@code 409}.
     */
    @DeleteMapping("/earlier-time")
    BookingView withdrawEarlierTime(@PathVariable UUID id, Actor actor) {
        return earlierTime.withdraw(id, actor);
    }

    /**
     * Клиент согласен приехать раньше: предложенное время становится временем визита. Нет предложения, ждущего ответа,
     * или его время прошло — {@code 409}.
     */
    @PostMapping("/earlier-time/accept")
    BookingView acceptEarlierTime(@PathVariable UUID id, Actor actor) {
        return earlierTime.respond(id, true, actor);
    }

    /**
     * Клиент отказался приехать раньше: остаётся прежнее время визита. Нет предложения, ждущего ответа, —
     * {@code 409}.
     */
    @PostMapping("/earlier-time/decline")
    BookingView declineEarlierTime(@PathVariable UUID id, Actor actor) {
        return earlierTime.respond(id, false, actor);
    }

    /**
     * Визит выполнен: фактический состав и итог — из выбранного при записи. Слот ещё не начался, срок исправления
     * отметки истёк, запись отменена или уже выполнена — {@code 409}.
     */
    @PostMapping("/complete")
    BookingView complete(@PathVariable UUID id, Actor actor) {
        return marks.complete(id, actor);
    }

    /**
     * Клиент не пришёл: признак обязательной предоплаты, фактический состав и итог стираются. Слот ещё не начался,
     * срок исправления отметки истёк, запись отменена или уже отмечена неявкой — {@code 409}.
     */
    @PostMapping("/no-show")
    BookingView markNoShow(@PathVariable UUID id, Actor actor) {
        return marks.markNoShow(id, actor);
    }

    /**
     * Фактический состав и итог выполненного визита; названия и цены — из каталога на момент правки. Визит не отмечен
     * выполненным или срок исправления истёк — {@code 409}; неверный состав — {@code 400}.
     */
    @PutMapping("/actual-services")
    BookingView updateActualServices(
            @PathVariable UUID id, @Valid @RequestBody UpdateActualServices body, Actor actor) {
        return marks.updateActualServices(id, body, actor);
    }
}
