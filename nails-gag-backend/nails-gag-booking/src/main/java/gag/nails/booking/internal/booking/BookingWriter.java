package gag.nails.booking.internal.booking;

import gag.nails.common.error.InternalException;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.schedule.error.SlotOccupiedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Запись в базу изменений, которые упираются в индекс {@code booking_active_slot_key} (на слот не
 * больше одной активной записи). Flush делается здесь, до обращения к {@code schedule}: иначе
 * нарушение всплыло бы внутри чужого модуля и было бы понято им неверно. Нарушение индекса — тот же
 * {@link SlotOccupiedException}, что и занятый слот (спецификация 7.6). После него транзакция
 * PostgreSQL прервана: исключению дают всплыть.
 */
@Component
class BookingWriter {

    private static final String ACTIVE_SLOT_INDEX = "booking_active_slot_key";

    private final BookingRepository bookings;

    BookingWriter(BookingRepository bookings) {
        this.bookings = bookings;
    }

    BookingEntity insert(BookingEntity booking) {
        try {
            return bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw slotTakenOr(e, booking);
        }
    }

    /** Сбрасывает в базу перенос на другой слот. */
    void flushMoved(BookingEntity booking) {
        try {
            bookings.flush();
        } catch (DataIntegrityViolationException e) {
            throw slotTakenOr(e, booking);
        }
    }

    private static RuntimeException slotTakenOr(DataIntegrityViolationException e, BookingEntity booking) {
        var name = ConstraintViolations.constraintName(e);
        if (name.filter(ACTIVE_SLOT_INDEX::equals).isPresent()) {
            return new SlotOccupiedException(booking.slotId());
        }
        return new InternalException("booking write violated constraint " + name.orElse("<unknown>"), e);
    }
}
