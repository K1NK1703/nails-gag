package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.booking.event.BookingCreated;
import gag.nails.booking.event.PrepaymentRequested;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingCreationTest extends BookingTestSupport {

    @Autowired
    CatalogService catalog;

    @Autowired
    BookingSettingsService settings;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void clientBooksFreeSlotAndItIsConfirmedAtOnce() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");

        var booking = book(anna, slot);

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.createdBy()).isEqualTo(BookingCreator.CLIENT);
        assertThat(booking.clientId()).isEqualTo(anna);
        assertThat(booking.slotId()).isEqualTo(slot.id());
        assertThat(booking.slotStartsAt()).isEqualTo(slot.startsAt());
        assertThat(booking.slotEndsAt()).isEqualTo(slot.endsAt());
        assertThat(booking.visitStart()).isEqualTo(slot.startsAt());
        assertThat(booking.prepaymentRequired()).isFalse();
        assertThat(booking.prepaymentDeadline()).isNull();
        assertThat(booking.actualServices()).isEmpty();
        assertThat(booking.finalAmount()).isNull();
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY)).isEmpty();
        assertThat(queries.activeOfClient(anna, Actor.client(anna)))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
    }

    @Test
    void bookingKeepsSnapshotOfMainServiceAndAddons() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");

        var booking = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH, DESIGN), "Короткие, квадрат"),
                Actor.client(anna));
        catalog.update(
                FRENCH,
                new UpdateService("Френч", "Описание", new BigDecimal("350.00"), null, "Доп. покрытие", true, false),
                master);

        var saved = queries.get(booking.id(), master);
        assertThat(saved.comment()).isEqualTo("Короткие, квадрат");
        assertThat(saved.plannedServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH, DESIGN);
        assertThat(saved.plannedServices())
                .extracting(BookedServiceView::kind)
                .containsExactly(ServiceKind.MAIN, ServiceKind.ADDON, ServiceKind.ADDON);
        assertThat(saved.plannedServices().get(0).title()).isEqualTo("Маникюр");
        assertThat(saved.plannedServices().get(1).price()).isEqualByComparingTo("300.00");
    }

    @Test
    void clientOwingPrepaymentGetsBookingThatAwaitsIt() {
        var anna = clientOwingPrepayment("Анна");

        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(booking.prepaymentRequired()).isTrue();
        assertThat(booking.prepaymentDeadline()).isEqualTo(TestTimeConfiguration.START.plus(Duration.ofHours(24)));
    }

    @Test
    void prepaymentDeadlineIsCappedByBookingCutoff() {
        var anna = clientOwingPrepayment("Анна");

        var booking = book(anna, slot(TODAY, "20:00"));

        // слот в 17:00 UTC: через 24 часа было бы поздно, срок — за два часа до начала
        assertThat(booking.prepaymentDeadline()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));
    }

    @Test
    void slotCloserThanCutoffIsClosedForClients() {
        var anna = client("Анна");
        var soon = slot(TODAY, "10:30");

        assertThatThrownBy(() -> book(anna, soon))
                .isInstanceOf(TooLateToBookException.class)
                .satisfies(
                        e -> assertThat(((TooLateToBookException) e).params()).containsEntry("cutoffMinutes", 120));
    }

    @Test
    void secondActiveBookingExceedsTheLimit() {
        var anna = client("Анна");
        book(anna, slot(MONDAY, "12:00"));
        var another = slot(TUESDAY, "12:00");

        assertThatThrownBy(() -> book(anna, another))
                .isInstanceOf(LimitReachedException.class)
                .satisfies(e -> assertThat(((LimitReachedException) e).params()).containsEntry("limit", 1));
    }

    @Test
    void bookingRequiresConsentsToCurrentDocuments() {
        var anna = clientWithoutConsents("Анна");
        var slot = slot(MONDAY, "12:00");

        assertThatThrownBy(() -> book(anna, slot)).isInstanceOf(ConsentMissingException.class);
    }

    @Test
    void occupiedSlotCannotBeBookedAgain() {
        var slot = slot(MONDAY, "12:00");
        book(client("Анна"), slot);
        var maria = client("Мария");

        assertThatThrownBy(() -> book(maria, slot)).isInstanceOf(SlotOccupiedException.class);
    }

    @Test
    void clientCannotBookForSomeoneElse() {
        var anna = client("Анна");
        var maria = client("Мария");
        var slot = slot(MONDAY, "12:00");

        assertThatThrownBy(() -> bookings.createByClient(manicure(anna, slot), Actor.client(maria)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bookings.createByClient(manicure(anna, slot), master))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void mainServiceMustBeMainAndAddonsMustBeAddons() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var actor = Actor.client(anna);

        assertThatThrownBy(() ->
                        bookings.createByClient(new CreateBooking(anna, slot.id(), FRENCH, List.of(), null), actor))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "mainServiceId"));
        assertThatThrownBy(() -> bookings.createByClient(
                        new CreateBooking(anna, slot.id(), MANICURE, List.of(MANICURE), null), actor))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).params()).containsEntry("field", "addonServiceIds"));
        assertThatThrownBy(() -> bookings.createByClient(
                        new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH, FRENCH), null), actor))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).params()).containsEntry("field", "addonServiceIds"));
    }

    @Test
    void hiddenServiceCannotBeBooked() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        catalog.deactivate(FRENCH, master);

        assertThatThrownBy(() -> bookings.createByClient(
                        new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna)))
                .isInstanceOf(ServiceInactiveException.class);
    }

    @Test
    void longCommentIsRejected() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var comment = "а".repeat(501);

        assertThatThrownBy(() -> bookings.createByClient(
                        new CreateBooking(anna, slot.id(), MANICURE, List.of(), comment), Actor.client(anna)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "comment"));
    }

    @Test
    void masterBooksAnyoneWithoutLimitConsentsOrCutoff() {
        var anna = clientWithoutConsents("Анна");

        var first = bookings.createByMaster(manicure(anna, slot(TODAY, "10:30")), false, master);
        var second = bookings.createByMaster(manicure(anna, slot(MONDAY, "12:00")), false, master);

        assertThat(first.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(first.createdBy()).isEqualTo(BookingCreator.MASTER);
        assertThat(queries.activeOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    void masterDecidesWhetherBookingAwaitsPrepayment() {
        var anna = client("Анна");
        var maria = clientOwingPrepayment("Мария");

        var awaiting = bookings.createByMaster(manicure(anna, slot(MONDAY, "12:00")), true, master);
        var confirmed = bookings.createByMaster(manicure(maria, slot(TUESDAY, "12:00")), false, master);

        assertThat(awaiting.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(awaiting.prepaymentDeadline()).isEqualTo(TestTimeConfiguration.START.plus(Duration.ofHours(24)));
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(maria)).isTrue();
    }

    @Test
    void masterRecordsPastVisitWithinCorrectionWindow() {
        var anna = client("Анна");

        var recorded = bookings.createByMaster(manicure(anna, slot(LocalDate.of(2026, 9, 28), "12:00")), false, master);
        var tooOld = slot(LocalDate.of(2026, 9, 20), "12:00");

        assertThat(recorded.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> bookings.createByMaster(manicure(anna, tooOld), false, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "slotId"));
    }

    @Test
    void prepaymentCannotBeAwaitedForPastSlot() {
        var anna = client("Анна");
        var past = slot(LocalDate.of(2026, 9, 28), "12:00");

        assertThatThrownBy(() -> bookings.createByMaster(manicure(anna, past), true, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(
                        e -> assertThat(((ValidationException) e).params()).containsEntry("field", "awaitPrepayment"));
    }

    @Test
    void clientCannotUseMasterCreation() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");

        assertThatThrownBy(() -> bookings.createByMaster(manicure(anna, slot), false, Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void creationPublishesEvents(PublishedEvents events) {
        var anna = clientOwingPrepayment("Анна");

        var booking = book(anna, slot(MONDAY, "12:00"));

        assertThat(events.ofType(BookingCreated.class)
                        .matching(e -> e.bookingId().equals(booking.id())
                                && e.status() == BookingStatus.AWAITING_PREPAYMENT
                                && e.occurredAt().equals(TestTimeConfiguration.START)))
                .hasSize(1);
        assertThat(events.ofType(PrepaymentRequested.class)
                        .matching(e -> e.bookingId().equals(booking.id())
                                && e.deadline().equals(booking.prepaymentDeadline())))
                .hasSize(1);
    }

    @Test
    void bookingIsReadBackFromDatabase() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");

        var created = bookings.createByClient(
                new CreateBooking(anna, slot.id(), MANICURE, List.of(FRENCH, DESIGN), "Короткие, квадрат"),
                Actor.client(anna));
        entityManager.flush();
        entityManager.clear();

        assertThat(queries.get(created.id(), master)).isEqualTo(created);
    }

    @Test
    void clientBooksExactlyAtCutoffButNotLater() {
        var anna = client("Анна");
        var maria = client("Мария");
        var first = slot(MONDAY, "12:00");
        var second = slot(TUESDAY, "12:00");

        timeIsBefore(first, Duration.ofMinutes(120));
        var booking = book(anna, first);
        timeIsBefore(second, Duration.ofMinutes(119));

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThatThrownBy(() -> book(maria, second)).isInstanceOf(TooLateToBookException.class);
    }

    @Test
    void slotStartingNowCannotBeBookedByClientEvenWithZeroCutoff() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        settings.update(new UpdateBookingSettings(1, 0, 24, 24, 50, 15, 7), master);
        timeIsBefore(slot, Duration.ZERO);

        assertThatThrownBy(() -> book(anna, slot)).isInstanceOf(TooLateToBookException.class);
    }

    @Test
    void bookingRequiresEveryConsent() {
        var anna = clientWithoutConsents("Анна");
        var maria = clientWithoutConsents("Мария");
        clients.recordConsent(
                anna,
                new RecordConsent(ConsentKind.PERSONAL_DATA, "2026-09-25", ConsentSource.ENTERED_BY_MASTER),
                master);
        clients.recordConsent(
                maria, new RecordConsent(ConsentKind.OFFER, "2026-09-25", ConsentSource.ENTERED_BY_MASTER), master);
        var slot = slot(MONDAY, "12:00");

        assertThatThrownBy(() -> book(anna, slot)).isInstanceOf(ConsentMissingException.class);
        assertThatThrownBy(() -> book(maria, slot)).isInstanceOf(ConsentMissingException.class);
    }
}
