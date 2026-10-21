package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingMaintenanceTest extends BookingTestSupport {

    @Autowired
    BookingMaintenance maintenance;

    @Autowired
    EarlierTimeOffers offers;

    @Test
    void overduePrepaymentCancelsBookingAndFreesSlot(PublishedEvents events) {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        clock.advance(Duration.ofHours(24).plusMinutes(1));

        var cancelled = maintenance.cancelOverduePrepayments();

        assertThat(cancelled).isEqualTo(1);
        var expired = queries.get(booking.id(), master);
        assertThat(expired.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(expired.cancelReason()).isEqualTo(CancelReason.PREPAYMENT_TIMEOUT);
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
        // признак у клиента остаётся: следующая запись снова будет ждать предоплату
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(events.ofType(BookingCancelled.class)
                        .matching(e ->
                                e.bookingId().equals(booking.id()) && e.reason() == CancelReason.PREPAYMENT_TIMEOUT))
                .hasSize(1);
    }

    @Test
    void prepaymentBeforeDeadlineIsNotTouched() {
        var booking = book(clientOwingPrepayment("Анна"), slot(MONDAY, "12:00"));
        clock.advance(Duration.ofHours(23));

        assertThat(maintenance.cancelOverduePrepayments()).isZero();
        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
    }

    @Test
    void repeatedRunIsHarmless() {
        book(clientOwingPrepayment("Анна"), slot(MONDAY, "12:00"));
        clock.advance(Duration.ofHours(25));

        assertThat(maintenance.cancelOverduePrepayments()).isEqualTo(1);
        assertThat(maintenance.cancelOverduePrepayments()).isZero();
    }

    @Test
    void pendingProposalExpiresAtSlotStart() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);

        assertThat(maintenance.expireEarlierTimeOffers()).isZero();
        clock.set(slot.startsAt());
        assertThat(maintenance.expireEarlierTimeOffers()).isEqualTo(1);

        var expired = queries.get(booking.id(), master);
        assertThat(expired.earlierTime().status()).isEqualTo(EarlierTimeStatus.EXPIRED);
        assertThat(expired.agreedStart()).isNull();
        assertThat(expired.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void answeredProposalsDoNotExpire() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);
        offers.respond(booking.id(), true, Actor.client(anna));
        clock.set(slot.startsAt());

        assertThat(maintenance.expireEarlierTimeOffers()).isZero();
        assertThat(queries.get(booking.id(), master).earlierTime().status()).isEqualTo(EarlierTimeStatus.ACCEPTED);
    }
}
