package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.clients.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class VisitMarksTest extends BookingTestSupport {

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void masterCompletesVisitAfterSlotStartAndActualCopiesPlanned() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        var completed = marks.complete(booking.id(), master);

        assertThat(completed.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(clock.instant());
        assertThat(completed.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);
        assertThat(completed.plannedServices()).hasSize(2);
        assertThat(completed.finalAmount()).isEqualByComparingTo("2800.00");
        // слот прошедшего визита остаётся занятым
        assertThat(scheduleQueries.slotsOfDay(MONDAY, master)).singleElement().matches(SlotView::occupied);
        assertThat(queries.historyOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
    }

    @Test
    void visitCannotBeMarkedBeforeSlotStart() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> marks.complete(booking.id(), master)).isInstanceOf(VisitNotStartedException.class);
        assertThatThrownBy(() -> marks.markNoShow(booking.id(), master)).isInstanceOf(VisitNotStartedException.class);
    }

    @Test
    void completedVisitWithPrepaymentClearsTheFlag() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        prepayments.markReceived(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.complete(booking.id(), master);

        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master)).first().satisfies(change -> {
            assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.VISIT_WITH_PREPAYMENT_COMPLETED);
            assertThat(change.bookingId()).isEqualTo(booking.id());
        });
    }

    @Test
    void completedVisitWithoutPrepaymentLeavesTheFlag() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(manicure(anna, slot), false, master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.complete(booking.id(), master);

        assertThat(owesPrepayment(anna)).isTrue();
    }

    @Test
    void noShowRequiresPrepaymentNextTime() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        var missed = marks.markNoShow(booking.id(), master);

        assertThat(missed.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(missed.noShowAt()).isEqualTo(clock.instant());
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .first()
                .satisfies(change -> assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.NO_SHOW));
    }

    @Test
    void noShowCorrectedToCompletedClearsTheFlag() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));
        marks.markNoShow(booking.id(), master);
        clock.advance(Duration.ofHours(1));

        var corrected = marks.complete(booking.id(), master);

        assertThat(corrected.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(corrected.noShowAt()).isNull();
        assertThat(corrected.actualServices()).hasSize(1);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .first()
                .satisfies(change -> assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.NO_SHOW_CORRECTED));
    }

    @Test
    void completedCorrectedToNoShowDropsActualsAndSetsTheFlag() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        clock.advance(Duration.ofHours(1));

        var corrected = marks.markNoShow(booking.id(), master);

        assertThat(corrected.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(corrected.completedAt()).isNull();
        assertThat(corrected.actualServices()).isEmpty();
        assertThat(corrected.finalAmount()).isNull();
        assertThat(corrected.plannedServices()).hasSize(1);
        assertThat(owesPrepayment(anna)).isTrue();
    }

    @Test
    void correctionWindowClosesAfterSevenDays() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofDays(7).plusMinutes(1));

        assertThatThrownBy(() -> marks.markNoShow(booking.id(), master))
                .isInstanceOf(CorrectionWindowClosedException.class)
                .satisfies(e -> assertThat(((CorrectionWindowClosedException) e).params())
                        .containsEntry("windowDays", 7));
    }

    @Test
    void masterAdjustsActualServicesAndAmount() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);

        var adjusted = marks.updateActualServices(
                booking.id(), new UpdateActualServices(MANICURE, List.of(DESIGN), new BigDecimal("2600.00")), master);

        assertThat(adjusted.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, DESIGN);
        assertThat(adjusted.finalAmount()).isEqualByComparingTo("2600.00");
        assertThat(adjusted.plannedServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);
        assertThat(queries.get(booking.id(), master).actualServices()).hasSize(2);
    }

    @Test
    void actualServicesAreOnlyForCompletedVisit() {
        var booking = book(client("Анна"), slot(MONDAY, "12:00"));

        assertThatThrownBy(() -> marks.updateActualServices(
                        booking.id(), new UpdateActualServices(MANICURE, List.of(), new BigDecimal("2500.00")), master))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void actualServicesNeedOneMainService() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);

        assertThatThrownBy(() -> marks.updateActualServices(
                        booking.id(), new UpdateActualServices(FRENCH, List.of(), new BigDecimal("300.00")), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "mainServiceId"));
    }

    @Test
    void cancelledBookingCannotBeMarked() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        bookings.cancel(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        assertThatThrownBy(() -> marks.complete(booking.id(), master)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> marks.markNoShow(booking.id(), master)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void completingTwiceIsRejected() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);

        assertThatThrownBy(() -> marks.complete(booking.id(), master)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void clientCannotMarkVisits() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        assertThatThrownBy(() -> marks.complete(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> marks.markNoShow(booking.id(), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void marksPublishEvents(PublishedEvents events) {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.markNoShow(booking.id(), master);
        marks.complete(booking.id(), master);

        assertThat(events.ofType(BookingNoShow.class)
                        .matching(e -> e.bookingId().equals(booking.id())))
                .hasSize(1);
        assertThat(events.ofType(BookingCompleted.class)
                        .matching(e -> e.bookingId().equals(booking.id())))
                .hasSize(1);
    }

    @Test
    void marksCorrectionsAndActualServicesSurviveDatabaseRoundTrip() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.complete(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        var completed = queries.get(booking.id(), master);
        assertThat(completed.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.finalAmount()).isEqualByComparingTo("2800.00");
        assertThat(completed.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);

        clock.advance(Duration.ofHours(1));
        marks.markNoShow(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        var missed = queries.get(booking.id(), master);
        assertThat(missed.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(missed.completedAt()).isNull();
        assertThat(missed.finalAmount()).isNull();
        assertThat(missed.actualServices()).isEmpty();
        assertThat(missed.plannedServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);

        clock.advance(Duration.ofHours(1));
        marks.complete(booking.id(), master);
        entityManager.flush();
        entityManager.clear();
        marks.updateActualServices(
                booking.id(), new UpdateActualServices(MANICURE, List.of(DESIGN), new BigDecimal("2600.00")), master);
        entityManager.flush();
        entityManager.clear();

        var corrected = queries.get(booking.id(), master);
        assertThat(corrected.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(corrected.completedAt()).isEqualTo(clock.instant());
        assertThat(corrected.noShowAt()).isNull();
        assertThat(corrected.finalAmount()).isEqualByComparingTo("2600.00");
        assertThat(corrected.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, DESIGN);
        assertThat(corrected.plannedServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);
    }
}
