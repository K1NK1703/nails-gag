package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.schedule.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class BookingAccessTest extends BookingTestSupport {

    private static final Actor READER = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));
    private static final Actor WRITER = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.WRITE));

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingSettingsService settings;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void readerSeesBookingsButChangesNothing() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);

        assertThat(queries.get(booking.id(), READER).id()).isEqualTo(booking.id());
        assertThat(queries.ofDay(MONDAY, READER)).extracting(BookingView::id).containsExactly(booking.id());
        assertThat(queries.ofPeriod(MONDAY, TUESDAY, READER)).hasSize(1);
        assertThat(queries.awaitingMark(READER)).isEmpty();
        assertThat(queries.awaitingPrepayment(READER)).isEmpty();
        assertThat(queries.masterNote(booking.id(), READER)).isEmpty();
        assertThat(queries.activeOfClient(anna, READER)).hasSize(1);
        assertThat(queries.historyOfClient(anna, READER)).isEmpty();

        var freeSlot = slot(TUESDAY, "12:00");
        assertThatThrownBy(() -> bookings.createByMaster(manicure(anna, freeSlot), false, READER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bookings.cancel(booking.id(), READER)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bookings.reschedule(booking.id(), freeSlot.id(), READER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bookings.updateMasterNote(booking.id(), "заметка", READER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> prepayments.markReceived(booking.id(), READER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> offers.propose(booking.id(), slot.startsAt().minus(Duration.ofHours(1)), READER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> marks.complete(booking.id(), READER)).isInstanceOf(AccessDeniedException.class);

        entityManager.flush();
        entityManager.clear();
        var unchanged = queries.get(booking.id(), READER);
        assertThat(unchanged.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(unchanged.slotId()).isEqualTo(slot.id());
        assertThat(unchanged.earlierTime()).isNull();
        assertThat(queries.masterNote(booking.id(), READER)).isEmpty();
        assertThat(scheduleQueries.freeSlots(TUESDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactly(freeSlot.id());
    }

    @Test
    void writerActsAsStaff() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsBefore(slot, Duration.ofHours(23));

        var cancelled = bookings.cancel(booking.id(), WRITER);

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.MASTER);
    }

    @Test
    void writerCancellingOwnBookingActsAsStaff() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var annaAtWork = Actor.of(UUID.randomUUID(), anna, Map.of(Section.BOOKINGS, AccessLevel.WRITE));
        timeIsBefore(slot, Duration.ofHours(23));

        var cancelled = bookings.cancel(booking.id(), annaAtWork);

        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.MASTER);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master)).isEmpty();
    }

    @Test
    void readerCannotRescheduleSomeoneElsesBooking() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        var freeSlot = slot(TUESDAY, "12:00");

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), freeSlot.id(), READER))
                .isInstanceOf(AccessDeniedException.class);

        entityManager.flush();
        entityManager.clear();
        assertThat(queries.get(booking.id(), master).slotId()).isEqualTo(slot.id());
        assertThat(scheduleQueries.freeSlots(TUESDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactly(freeSlot.id());
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();
    }

    @Test
    void writerRespondsToOfferOnSomeoneElsesBooking() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        var earlier = slot.startsAt().minus(Duration.ofMinutes(90));
        offers.propose(booking.id(), earlier, WRITER);

        var accepted = offers.respond(booking.id(), true, WRITER);

        assertThat(accepted.earlierTime().status()).isEqualTo(EarlierTimeStatus.ACCEPTED);
        assertThat(accepted.agreedStart()).isEqualTo(earlier);
    }

    @Test
    void settingsHaveTheirOwnSection() {
        var command = new UpdateBookingSettings(2, 120, 24, 48, 50, 15, 7);

        assertThatThrownBy(() -> settings.update(command, WRITER)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.history(WRITER)).isInstanceOf(AccessDeniedException.class);

        var settingsReader = Actor.of(null, null, Map.of(Section.BOOKING_SETTINGS, AccessLevel.READ));
        assertThat(settings.history(settingsReader)).isEmpty();
        assertThatThrownBy(() -> settings.update(command, settingsReader)).isInstanceOf(AccessDeniedException.class);

        var settingsWriter = Actor.of(null, null, Map.of(Section.BOOKING_SETTINGS, AccessLevel.WRITE));
        assertThat(settings.update(command, settingsWriter).maxActiveBookings()).isEqualTo(2);
    }

    @Test
    void confirmingWithoutPrepaymentNeedsClientsSectionToo() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        assertThatThrownBy(() -> prepayments.confirmWithoutPrepayment(booking.id(), WRITER))
                .isInstanceOf(AccessDeniedException.class);
        entityManager.flush();
        entityManager.clear();
        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(owesPrepayment(anna)).isTrue();

        var staff =
                Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.WRITE, Section.CLIENTS, AccessLevel.WRITE));
        var confirmed = prepayments.confirmWithoutPrepayment(booking.id(), staff);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(anna)).isFalse();
    }

    @Test
    void noShowCorrectionWorksWithoutClientsSection() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        marks.markNoShow(booking.id(), WRITER);
        assertThat(owesPrepayment(anna)).isTrue();
        clock.advance(Duration.ofHours(1));

        assertThatCode(() -> marks.complete(booking.id(), WRITER)).doesNotThrowAnyException();
        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(owesPrepayment(anna)).isFalse();
    }

    @Test
    void staffMemberWithoutWriteCancelsOwnBookingAsClient() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var annaAtWork = Actor.of(UUID.randomUUID(), anna, Map.of(Section.BOOKINGS, AccessLevel.READ));
        timeIsBefore(slot, Duration.ofHours(23));

        var cancelled = bookings.cancel(booking.id(), annaAtWork);

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT_LATE);
    }
}
