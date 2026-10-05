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
 * клиент. У записи одновременно не больше одного предложения, ждущего ответа.
 */
public interface EarlierTimeOffers {

    /**
     * Мастер предлагает клиенту подтверждённой записи приехать раньше — в день слота, позже
     * «сейчас» и раньше начала слота. Новое предложение заменяет прежнее; если прежнее было принято,
     * согласованное раннее время сбрасывается до ответа на новое.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не подтверждена или предложение уже ждёт ответа
     * @throws ValidationException если время не в день слота или не между «сейчас» и началом слота
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView propose(UUID bookingId, Instant proposedStart, Actor actor);

    /**
     * Мастер отзывает предложение, пока клиент не ответил.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если предложения, ждущего ответа, нет
     * @throws AccessDeniedException если действует не мастер
     */
    BookingView withdraw(UUID bookingId, Actor actor);

    /**
     * Ответ на предложение: согласие назначает более раннее время визита, отказ оставляет прежнее.
     * Клиент отвечает по своей записи; мастер вносит ответ, полученный по телефону, по любой.
     * Согласиться можно, пока предложенное время не наступило.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если предложения, ждущего ответа, нет или его время прошло
     * @throws AccessDeniedException если клиент отвечает по чужой записи
     */
    BookingView respond(UUID bookingId, boolean accept, Actor actor);
}
