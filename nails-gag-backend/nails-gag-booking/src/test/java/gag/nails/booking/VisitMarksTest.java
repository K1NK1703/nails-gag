package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.CatalogService;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SlotView;
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

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    CatalogService catalog;

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

    @Test
    void noShowCorrectionKeepsFlagSetBeforeTheNoShow() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(manicure(anna, slot), false, master);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));
        marks.markNoShow(booking.id(), master);
        clock.advance(Duration.ofHours(1));

        var corrected = marks.complete(booking.id(), master);

        assertThat(corrected.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.MASTER_SET);
    }

    @Test
    void noShowCorrectionKeepsFlagReassertedByMasterAfterTheNoShow() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));
        marks.markNoShow(booking.id(), master);
        clock.advance(Duration.ofMinutes(10));
        clients.setPrepaymentRequired(anna, true, "Опаздывает не первый раз", master);
        clock.advance(Duration.ofHours(1));

        var corrected = marks.complete(booking.id(), master);

        assertThat(corrected.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.MASTER_SET, PrepaymentFlagReason.NO_SHOW);
    }

    @Test
    void noShowOfClientAlreadyOwingPrepaymentAddsNoJournalRow() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(manicure(anna, slot), false, master);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        marks.markNoShow(booking.id(), master);

        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(PrepaymentFlagReason.MASTER_SET);
    }

    @Test
    void markIsAllowedExactlyAtSlotStartAndCorrectionExactlyAtWindowEnd() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);

        timeIsAfterStartOf(slot, Duration.ZERO);
        assertThat(marks.complete(booking.id(), master).status()).isEqualTo(BookingStatus.COMPLETED);

        timeIsAfterStartOf(slot, Duration.ofDays(7));
        assertThat(marks.markNoShow(booking.id(), master).status()).isEqualTo(BookingStatus.NO_SHOW);

        timeIsAfterStartOf(slot, Duration.ofDays(7).plusSeconds(1));
        assertThatThrownBy(() -> marks.complete(booking.id(), master))
                .isInstanceOf(CorrectionWindowClosedException.class);
    }

    @Test
    void actualServicesCannotBeChangedAfterCorrectionWindow() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofDays(7).plusSeconds(1));

        assertThatThrownBy(() -> marks.updateActualServices(
                        booking.id(),
                        new UpdateActualServices(MANICURE, List.of(DESIGN), new BigDecimal("2700.00")),
                        master))
                .isInstanceOf(CorrectionWindowClosedException.class);
    }

    @Test
    void actualServicesMayIncludeServiceHiddenAfterTheVisit() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        catalog.deactivate(DESIGN, master);

        var adjusted = marks.updateActualServices(
                booking.id(), new UpdateActualServices(MANICURE, List.of(DESIGN), new BigDecimal("2700.00")), master);

        assertThat(adjusted.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, DESIGN);
    }

    @Test
    void bookingAwaitingPrepaymentCanBeCompleted() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.complete(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        var stored = queries.get(booking.id(), master);
        assertThat(stored.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(stored.prepaymentDeadline()).isNull();
        assertThat(stored.finalAmount()).isEqualByComparingTo("2500.00");
        // предоплаты не было — признак остаётся
        assertThat(owesPrepayment(anna)).isTrue();
    }

    @Test
    void bookingAwaitingPrepaymentCanBeMarkedNoShow() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        marks.markNoShow(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        var stored = queries.get(booking.id(), master);
        assertThat(stored.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(stored.prepaymentDeadline()).isNull();
        assertThat(stored.noShowAt()).isEqualTo(clock.instant());
    }

    @Test
    void markExpiresPendingEarlierTimeOffer() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        marks.complete(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        var stored = queries.get(booking.id(), master);
        assertThat(stored.earlierTime().status()).isEqualTo(EarlierTimeStatus.EXPIRED);
        assertThat(stored.agreedStart()).isNull();
        assertThatThrownBy(() -> offers.respond(booking.id(), false, Actor.client(anna)))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void noShowExpiresPendingEarlierTimeOffer() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        marks.markNoShow(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(booking.id(), master).earlierTime().status()).isEqualTo(EarlierTimeStatus.EXPIRED);
        assertThatThrownBy(() -> offers.respond(booking.id(), false, Actor.client(anna)))
                .isInstanceOf(InvalidTransitionException.class);
    }
}
