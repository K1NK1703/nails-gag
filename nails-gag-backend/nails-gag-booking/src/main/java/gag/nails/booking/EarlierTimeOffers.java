package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.time.Instant;
import java.util.UUID;

/**
 * Предложение приехать раньше: слот остаётся прежним, меняется только время, к которому приходит
 * клиент. У записи одновременно не больше одного предложения, ждущего ответа. Согласованное раннее
 * время меняется только принятием нового предложения: пока новое ждёт ответа, а также если оно
 * отклонено, отозвано или истекло, действует прежнее согласованное время. Сбрасывает его только
 * перенос записи.
 */
public interface EarlierTimeOffers {

    /**
     * Сотрудник с изменением раздела «Записи» предлагает клиенту подтверждённой записи приехать раньше — в день слота, позже
     * «сейчас» и раньше начала слота. Новое предложение заменяет прежнее; если прежнее было принято,
     * согласованное по нему время остаётся в силе, пока клиент не примет новое.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не подтверждена или предложение уже ждёт ответа
     * @throws ValidationException если время не в день слота или не между «сейчас» и началом слота
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView propose(UUID bookingId, Instant proposedStart, Actor actor);

    /**
     * Сотрудник отзывает предложение, пока клиент не ответил. Время, согласованное по более раннему
     * предложению, остаётся в силе.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если предложения, ждущего ответа, нет
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView withdraw(UUID bookingId, Actor actor);

    /**
     * Ответ на предложение: согласие назначает предложенное время визита (заменяя согласованное
     * раньше), отказ оставляет прежнее — согласованное по более раннему предложению или начало слота.
     * Клиент отвечает по своей записи; сотрудник с изменением «Записей» вносит ответ, полученный по
     * телефону, по любой.
     * Согласиться можно, пока предложенное время не наступило.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если предложения, ждущего ответа, нет или его время прошло
     * @throws AccessDeniedException если актор без изменения «Записей» отвечает по чужой записи
     */
    BookingView respond(UUID bookingId, boolean accept, Actor actor);
}
