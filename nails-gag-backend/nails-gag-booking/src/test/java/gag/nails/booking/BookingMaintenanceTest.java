package gag.nails.booking;

import static gag.nails.booking.ReferenceUploads.signedIn;
import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

@ModuleTest
class BookingMaintenanceTest extends BookingTestSupport {

    @Autowired
    BookingMaintenance maintenance;

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    BookingReferences references;

    @Autowired
    VisitMarks marks;

    @Autowired
    FileStorage files;

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

    @Test
    void referencesRemovedThirtyDaysAfterVisit() {
        var anna = client("Анна");
        var monday = slot(MONDAY, "12:00");
        var completed = book(anna, monday);
        var completedFile = reference(completed, anna);
        var tuesday = slot(TUESDAY, "12:00");
        var noShow = bookings.createByMaster(manicure(anna, tuesday), false, master);
        var noShowFile = reference(noShow, anna);
        timeIsAfterStartOf(tuesday, Duration.ofHours(1));
        marks.complete(completed.id(), master);
        marks.markNoShow(noShow.id(), master);

        // Отметки поставлены позже визита, а срок считается от начала визита.
        clock.set(monday.startsAt().plus(Duration.ofDays(30)));
        assertThat(maintenance.removeExpiredReferences()).isZero();
        assertThat(references.list(completed.id(), master)).hasSize(1);

        clock.set(monday.startsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertThat(references.list(completed.id(), master)).isEmpty();
        assertThat(files.find(completedFile)).isEmpty();
        assertThat(references.list(noShow.id(), master)).hasSize(1);

        clock.set(tuesday.startsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertThat(references.list(noShow.id(), master)).isEmpty();
        assertThat(files.find(noShowFile)).isEmpty();
        assertThat(maintenance.removeExpiredReferences()).isZero();
    }

    @Test
    void referencesExpireThirtyDaysAfterAgreedEarlierStart() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);
        var agreedStart = slot.startsAt().minus(Duration.ofMinutes(90));
        offers.propose(booking.id(), agreedStart, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(1));
        marks.complete(booking.id(), master);

        clock.set(agreedStart.plus(Duration.ofDays(30)).plusSeconds(1));

        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertThat(files.find(file)).isEmpty();
    }

    @Test
    void referencesOfActiveBookingKept() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);

        // Визит так и не отмечен: запись активна, референс нужен мастеру.
        timeIsAfterStartOf(slot, Duration.ofDays(60));

        assertThat(maintenance.removeExpiredReferences()).isZero();
        assertThat(references.list(booking.id(), master)).hasSize(1);
        assertThat(files.find(file)).isPresent();
    }

    @Test
    void referencesRemovedThirtyDaysAfterCancellation() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);
        clock.advance(Duration.ofHours(1));
        var cancelledAt = bookings.cancel(booking.id(), Actor.client(anna)).cancelledAt();

        clock.set(cancelledAt.plus(Duration.ofDays(30)));
        assertThat(maintenance.removeExpiredReferences()).isZero();

        clock.set(cancelledAt.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertThat(references.list(booking.id(), master)).isEmpty();
        assertThat(files.find(file)).isEmpty();
    }

    /** Клиент прикладывает к записи референс, загруженный своим аккаунтом. */
    private UUID reference(BookingView booking, UUID clientId) {
        var actor = signedIn(clientId);
        return references
                .add(booking.id(), ReferenceUploads.upload(files, actor), actor)
                .fileId();
    }
}
