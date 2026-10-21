package gag.nails.booking.internal.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Загрузка записи с проверкой, что она есть и что действующему лицу можно её видеть. Чтение — без
 * блокировки, изменяющие операции берут запись через {@code …ForUpdate}.
 */
@Component
public class BookingFinder {

    private final BookingRepository bookings;

    BookingFinder(BookingRepository bookings) {
        this.bookings = bookings;
    }

    BookingEntity require(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** Сотрудник с чтением «Записей» — любую запись, клиент — только свою. */
    public BookingEntity requireAccessible(UUID bookingId, Actor actor) {
        var booking = require(bookingId);
        actor.requireReadOrSelf(Section.BOOKINGS, booking.clientId());
        return booking;
    }

    /** Запись для изменения — под блокировкой строки: параллельные операции над ней идут по очереди. */
    public BookingEntity requireForUpdate(UUID bookingId) {
        return bookings.findLockedById(bookingId).orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** Как {@link #requireForUpdate}; сотруднику нужен уровень изменения «Записей», клиент меняет только свою. */
    public BookingEntity requireAccessibleForUpdate(UUID bookingId, Actor actor) {
        var booking = requireForUpdate(bookingId);
        actor.requireWriteOrSelf(Section.BOOKINGS, booking.clientId());
        return booking;
    }
}
