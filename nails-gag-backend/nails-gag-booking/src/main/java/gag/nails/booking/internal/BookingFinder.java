package gag.nails.booking.internal;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.common.actor.Actor;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Загрузка записи с проверкой, что она есть и что действующему лицу можно её видеть. Чтение — без
 * блокировки, изменяющие операции берут запись через {@code …ForUpdate}.
 */
@Component
class BookingFinder {

    private final BookingRepository bookings;

    BookingFinder(BookingRepository bookings) {
        this.bookings = bookings;
    }

    BookingEntity require(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** Мастер — любую запись, клиент — только свою. */
    BookingEntity requireAccessible(UUID bookingId, Actor actor) {
        var booking = require(bookingId);
        actor.requireMasterOrSelf(booking.clientId());
        return booking;
    }

    /** Запись для изменения — под блокировкой строки: параллельные операции над ней идут по очереди. */
    BookingEntity requireForUpdate(UUID bookingId) {
        return bookings.findLockedById(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** Как {@link #requireForUpdate}, с той же проверкой доступа, что в {@link #requireAccessible}. */
    BookingEntity requireAccessibleForUpdate(UUID bookingId, Actor actor) {
        var booking = requireForUpdate(bookingId);
        actor.requireMasterOrSelf(booking.clientId());
        return booking;
    }
}
