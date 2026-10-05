package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.SlotView;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class EarlierTimeTest extends BookingTestSupport {

    @Autowired
    EarlierTimeOffers offers;

    @PersistenceContext
    EntityManager entityManager;

    private UUID anna;
    private SlotView slot;
    private BookingView booking;

    /** На полтора часа раньше начала слота, в тот же день: 10:30 вместо 12:00. */
    private Instant earlier;

    @BeforeEach
    void bookConfirmedVisit() {
        anna = client("Анна");
        slot = slot(MONDAY, "12:00");
        booking = book(anna, slot);
        earlier = slot.startsAt().minus(Duration.ofMinutes(90));
    }

    @Test
    void masterProposesEarlierTimeOnTheSameDay() {
        var proposed = offers.propose(booking.id(), earlier, master);

        assertThat(proposed.earlierTime())
                .isEqualTo(new EarlierTimeView(earlier, TestTimeConfiguration.START, EarlierTimeStatus.PENDING));
        assertThat(proposed.agreedStart()).isNull();
        assertThat(proposed.visitStart()).isEqualTo(slot.startsAt());
    }

    @Test
    void proposalMustBeOnSlotDayBetweenNowAndSlotStart() {
        var afterStart = slot.startsAt().plus(Duration.ofMinutes(30));
        var dayBefore = earlier.minus(Duration.ofDays(1));

        assertThatThrownBy(() -> offers.propose(booking.id(), afterStart, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "proposedStart"));
        assertThatThrownBy(() -> offers.propose(booking.id(), dayBefore, master))
                .isInstanceOf(ValidationException.class);

        timeIsBefore(slot, Duration.ofHours(1));
        assertThatThrownBy(() -> offers.propose(booking.id(), earlier, master)).isInstanceOf(ValidationException.class);
    }

    @Test
    void clientAcceptsAndComesEarlier() {
        offers.propose(booking.id(), earlier, master);

        var accepted = offers.respond(booking.id(), true, Actor.client(anna));

        assertThat(accepted.earlierTime().status()).isEqualTo(EarlierTimeStatus.ACCEPTED);
        assertThat(accepted.agreedStart()).isEqualTo(earlier);
        assertThat(accepted.visitStart()).isEqualTo(earlier);
        assertThat(accepted.slotStartsAt()).isEqualTo(slot.startsAt());
    }

    @Test
    void clientDeclinesAndTimeStays() {
        offers.propose(booking.id(), earlier, master);

        var declined = offers.respond(booking.id(), false, Actor.client(anna));

        assertThat(declined.earlierTime().status()).isEqualTo(EarlierTimeStatus.DECLINED);
        assertThat(declined.agreedStart()).isNull();
        assertThat(declined.visitStart()).isEqualTo(slot.startsAt());
    }

    @Test
    void masterRecordsAnswerGivenByPhone() {
        offers.propose(booking.id(), earlier, master);

        var accepted = offers.respond(booking.id(), true, master);

        assertThat(accepted.agreedStart()).isEqualTo(earlier);
    }

    @Test
    void secondProposalWhileOneIsPendingIsRejected() {
        offers.propose(booking.id(), earlier, master);

        assertThatThrownBy(() -> offers.propose(booking.id(), earlier.plus(Duration.ofMinutes(30)), master))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void masterWithdrawsPendingProposal() {
        offers.propose(booking.id(), earlier, master);

        var withdrawn = offers.withdraw(booking.id(), master);

        assertThat(withdrawn.earlierTime().status()).isEqualTo(EarlierTimeStatus.WITHDRAWN);
        assertThatThrownBy(() -> offers.respond(booking.id(), true, Actor.client(anna)))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> offers.withdraw(booking.id(), master)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void onlyConfirmedBookingCanGetProposal() {
        var maria = clientOwingPrepayment("Мария");
        var awaiting = book(maria, slot(TUESDAY, "12:00"));

        assertThatThrownBy(() ->
                        offers.propose(awaiting.id(), awaiting.slotStartsAt().minus(Duration.ofMinutes(90)), master))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void acceptanceAfterProposedTimeIsRejected() {
        offers.propose(booking.id(), earlier, master);
        clock.set(earlier.plus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> offers.respond(booking.id(), true, Actor.client(anna)))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void rescheduleResetsAgreedTime() {
        offers.propose(booking.id(), earlier, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        var another = slot(TUESDAY, "15:00");

        var moved = bookings.reschedule(booking.id(), another.id(), Actor.client(anna));

        assertThat(moved.agreedStart()).isNull();
        assertThat(moved.visitStart()).isEqualTo(another.startsAt());
    }

    @Test
    void rescheduleAndCancellationWithdrawPendingProposal() {
        offers.propose(booking.id(), earlier, master);
        var another = slot(TUESDAY, "15:00");

        var moved = bookings.reschedule(booking.id(), another.id(), master);
        assertThat(moved.earlierTime().status()).isEqualTo(EarlierTimeStatus.WITHDRAWN);

        offers.propose(booking.id(), another.startsAt().minus(Duration.ofHours(1)), master);
        var cancelled = bookings.cancel(booking.id(), master);
        assertThat(cancelled.earlierTime().status()).isEqualTo(EarlierTimeStatus.WITHDRAWN);
    }

    @Test
    void strangerCannotAnswerAndClientCannotPropose() {
        offers.propose(booking.id(), earlier, master);
        var maria = client("Мария");

        assertThatThrownBy(() -> offers.respond(booking.id(), true, Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> offers.propose(booking.id(), earlier, Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> offers.withdraw(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void proposalAndAnswerPublishEvents(PublishedEvents events) {
        offers.propose(booking.id(), earlier, master);
        offers.respond(booking.id(), true, Actor.client(anna));

        assertThat(events.ofType(EarlierTimeProposed.class)
                        .matching(e -> e.bookingId().equals(booking.id())
                                && e.proposedStart().equals(earlier)))
                .hasSize(1);
        assertThat(events.ofType(EarlierTimeAccepted.class)
                        .matching(e -> e.bookingId().equals(booking.id())
                                && e.agreedStart().equals(earlier)))
                .hasSize(1);
        assertThat(events.ofType(EarlierTimeDeclined.class)).isEmpty();
    }

    @Test
    void acceptedOfferAndItsWithdrawalOnRescheduleSurviveDatabaseRoundTrip() {
        offers.propose(booking.id(), earlier, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        entityManager.flush();
        entityManager.clear();

        var accepted = queries.get(booking.id(), master);
        assertThat(accepted.earlierTime().status()).isEqualTo(EarlierTimeStatus.ACCEPTED);
        assertThat(accepted.agreedStart()).isEqualTo(earlier);

        var another = slot(TUESDAY, "15:00");
        bookings.reschedule(booking.id(), another.id(), master);
        entityManager.flush();
        entityManager.clear();

        var moved = queries.get(booking.id(), master);
        assertThat(moved.slotId()).isEqualTo(another.id());
        assertThat(moved.earlierTime().status()).isEqualTo(EarlierTimeStatus.WITHDRAWN);
        assertThat(moved.agreedStart()).isNull();
        assertThat(moved.visitStart()).isEqualTo(another.startsAt());
    }

    @Test
    void newProposalAfterAcceptedOneDropsAgreedTimeUntilAnswered() {
        offers.propose(booking.id(), earlier, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        var evenEarlier = earlier.minus(Duration.ofMinutes(30));

        offers.propose(booking.id(), evenEarlier, master);
        entityManager.flush();
        entityManager.clear();

        var proposed = queries.get(booking.id(), master);
        assertThat(proposed.earlierTime().status()).isEqualTo(EarlierTimeStatus.PENDING);
        assertThat(proposed.earlierTime().proposedStart()).isEqualTo(evenEarlier);
        assertThat(proposed.agreedStart()).isNull();
        assertThat(proposed.visitStart()).isEqualTo(slot.startsAt());
    }
}
