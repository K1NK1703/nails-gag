package gag.nails.booking.internal;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.common.actor.Actor;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Загрузка записи с проверкой, что она есть и что действующему лицу можно её видеть. */
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
}
