package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.clients.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class PrepaymentTest extends BookingTestSupport {

    @Autowired
    BookingPrepayments prepayments;

    @Test
    void masterMarksPrepaymentReceivedAndBookingIsConfirmed() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        var confirmed = prepayments.markReceived(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.prepaymentReceivedAt()).isEqualTo(TestTimeConfiguration.START);
        assertThat(confirmed.prepaymentDeadline()).isNull();
        assertThat(confirmed.prepaymentRequired()).isTrue();
        // признак у клиента остаётся до выполненного визита
        assertThat(owesPrepayment(anna)).isTrue();
    }

    @Test
    void masterConfirmsWithoutPrepaymentAndWaivesTheFlag() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        var confirmed = prepayments.confirmWithoutPrepayment(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.prepaymentReceivedAt()).isNull();
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .first()
                .satisfies(change -> assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.MASTER_WAIVED));
    }

    @Test
    void onlyBookingAwaitingPrepaymentCanBeConfirmed() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> prepayments.markReceived(booking.id(), master))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> prepayments.confirmWithoutPrepayment(booking.id(), master))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void clientCannotConfirmPrepayment() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> prepayments.markReceived(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> prepayments.confirmWithoutPrepayment(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void confirmationPublishesEvent(PublishedEvents events) {
        var booking = book(clientOwingPrepayment("Анна"), slot(MONDAY, "12:00"));

        prepayments.markReceived(booking.id(), master);

        assertThat(events.ofType(BookingConfirmed.class)
                        .matching(e -> e.bookingId().equals(booking.id()) && e.prepaymentReceived()))
                .hasSize(1);
    }

    @Test
    void confirmationWithoutPrepaymentLeavesJournalOfClientWithoutFlagUntouched() {
        var anna = client("Анна");
        var booking = bookings.createByMaster(manicure(anna, slot(MONDAY, "12:00")), true, master);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        var confirmed = prepayments.confirmWithoutPrepayment(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master)).isEmpty();
    }
}
