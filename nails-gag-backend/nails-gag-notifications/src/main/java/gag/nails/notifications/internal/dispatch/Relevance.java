package gag.nails.notifications.internal.dispatch;

import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.notifications.model.MessageKind;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Актуальность сообщения при отправке (спека §5.2) по текущему состоянию записи — чистая функция.
 *
 * <ul>
 *   <li>Напоминания — запись подтверждена и визит начинается тогда же, когда при расчёте напоминания: срок жизни
 *       напоминания ({@code expires_at}) и есть начало визита на момент расчёта. Ждущая предоплату запись напоминаний
 *       не получает, а перенос или принятое «пораньше» делают прежние напоминания неактуальными.
 *   <li>Просьба о предоплате — запись всё ещё ждёт предоплату.
 *   <li>Предложение прийти раньше — оно всё ещё ждёт ответа, и это то самое предложение: время его создания совпадает с
 *       моментом события ({@code occurred_at}).
 *   <li>Остальные — факты, отправляются всегда.
 * </ul>
 *
 * Записи нет — неактуально всё: без неё не собрать ни получателя, ни текст.
 */
final class Relevance {

    private Relevance() {}

    /**
     * @param expiresAt срок жизни сообщения
     * @param occurredAt момент события сообщения
     */
    static boolean relevant(MessageKind kind, @Nullable BookingView booking, Instant expiresAt, Instant occurredAt) {
        if (booking == null) {
            return false;
        }
        return switch (kind) {
            case REMINDER_DAY_BEFORE, REMINDER_HOURS_BEFORE ->
                booking.status() == BookingStatus.CONFIRMED
                        && booking.visitStart().equals(expiresAt);
            case PREPAYMENT_REQUESTED -> booking.status() == BookingStatus.AWAITING_PREPAYMENT;
            case EARLIER_PROPOSED -> offerPending(booking, occurredAt);
            case BOOKING_RECEIVED,
                    BOOKED_BY_MASTER,
                    CONFIRMED,
                    CANCELLED_BY_CLIENT,
                    CANCELLED,
                    RESCHEDULED,
                    STAFF_NEW_BOOKING,
                    STAFF_CLIENT_CANCELLED,
                    STAFF_PREPAYMENT_TIMEOUT,
                    STAFF_CLIENT_RESCHEDULED,
                    STAFF_EARLIER_ACCEPTED,
                    STAFF_EARLIER_DECLINED -> true;
        };
    }

    private static boolean offerPending(BookingView booking, Instant occurredAt) {
        var offer = booking.earlierTime();
        return offer != null
                && offer.status() == EarlierTimeStatus.PENDING
                && offer.proposedAt().equals(occurredAt);
    }
}
