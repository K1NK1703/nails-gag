package gag.nails.booking.internal.maintenance;

import gag.nails.booking.BookingMaintenance;
import gag.nails.booking.internal.booking.BookingRepository;
import gag.nails.booking.internal.reference.ReferenceRetention;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.EarlierTimeStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class DefaultBookingMaintenance implements BookingMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DefaultBookingMaintenance.class);

    private final BookingRepository bookings;
    private final BookingTimeouts timeouts;
    private final ReferenceRetention references;
    private final BookingProperties properties;
    private final Clock clock;

    DefaultBookingMaintenance(
            BookingRepository bookings,
            BookingTimeouts timeouts,
            ReferenceRetention references,
            BookingProperties properties,
            Clock clock) {
        this.bookings = bookings;
        this.timeouts = timeouts;
        this.references = references;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int cancelOverduePrepayments() {
        var overdue = bookings.idsWithPrepaymentDeadlineBefore(BookingStatus.AWAITING_PREPAYMENT, clock.instant());
        return each(overdue, timeouts::cancelOverduePrepayment);
    }

    @Override
    public int expireEarlierTimeOffers() {
        var unanswered = bookings.idsWithOfferAndSlotStartedBy(EarlierTimeStatus.PENDING, clock.instant());
        return each(unanswered, timeouts::expireEarlierTime);
    }

    @Override
    public int removeExpiredReferences() {
        var retention = Duration.ofDays(properties.referenceRetentionDays());
        return references.removeExpired(
                clock.instant().truncatedTo(ChronoUnit.MICROS).minus(retention));
    }

    /** Сбой на одной записи записывается в лог и не мешает остальным; следующий запуск попробует снова. */
    private static int each(List<UUID> bookingIds, Predicate<UUID> step) {
        var done = 0;
        for (var bookingId : bookingIds) {
            try {
                if (step.test(bookingId)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("booking maintenance failed for booking {}", bookingId, e);
            }
        }
        return done;
    }
}
