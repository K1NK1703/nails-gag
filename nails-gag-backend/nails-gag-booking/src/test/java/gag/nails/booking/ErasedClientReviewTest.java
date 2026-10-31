package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;

/**
 * Удаление аккаунта не отменяет записи (спецификация финансов §3.3а): активные записи с ненаступившим визитом
 * остаются, получают отметку обезличивания и ждут решения мастера («на рассмотрении»).
 */
@ModuleTest
class ErasedClientReviewTest extends BookingTestSupport {

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    BookingMaintenance maintenance;

    @Autowired
    EarlierTimeOffers offers;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void eraseKeepsUpcomingBookingsActive(PublishedEvents events) {
        var anna = client("Анна");
        var confirmed = book(anna, slot(MONDAY, "12:00"));
        var awaiting = bookings.createByMaster(manicure(anna, slot(TUESDAY, "12:00")), true, master);
        assertThat(awaiting.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        clients.erase(anna);

        assertThat(reloaded(confirmed).status()).isEqualTo(BookingStatus.CONFIRMED);
        var stillAwaiting = reloaded(awaiting);
        assertThat(stillAwaiting.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(stillAwaiting.prepaymentDeadline()).isEqualTo(awaiting.prepaymentDeadline());
        assertThat(scheduleQueries.freeSlots(MONDAY, TUESDAY)).isEmpty();
        assertThat(queries.activeOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactlyInAnyOrder(confirmed.id(), awaiting.id());
        assertThat(events.ofType(BookingCancelled.class)
                        .matching(e -> e.clientId().equals(anna)))
                .isEmpty();
    }

    @Test
    void eraseMarksBookingsForReview() {
        var anna = client("Анна");
        var completedSlot = slot(MONDAY, "10:00");
        var completed = bookings.createByMaster(manicure(anna, completedSlot), false, master);
        var startedSlot = slot(MONDAY, "12:00");
        var started = bookings.createByMaster(manicure(anna, startedSlot), false, master);
        var upcoming = bookings.createByMaster(manicure(anna, slot(TUESDAY, "12:00")), false, master);
        var cancelled = bookings.createByMaster(manicure(anna, slot(TUESDAY, "15:00")), false, master);
        bookings.cancel(cancelled.id(), master);
        timeIsAfterStartOf(startedSlot, Duration.ofMinutes(30));
        marks.complete(completed.id(), master);
        var erasedAt = clock.instant();

        clients.erase(anna);

        assertThat(reloaded(upcoming).clientErasedAt()).isEqualTo(erasedAt);
        // Начатый визит ждёт отметки как обычно; завершённые и отменённые решения не требуют.
        assertThat(reloaded(started).clientErasedAt()).isNull();
        assertThat(reloaded(completed).clientErasedAt()).isNull();
        assertThat(reloaded(cancelled).clientErasedAt()).isNull();
    }

    @Test
    void underReviewListsActiveMarkedBookingsByVisitStart() {
        var anna = client("Анна");
        var tuesday = bookings.createByMaster(manicure(anna, slot(TUESDAY, "10:00")), false, master);
        var monday = bookings.createByMaster(manicure(anna, slot(MONDAY, "15:00")), false, master);
        var earlierSlot = slot(MONDAY, "18:00");
        var earlier = bookings.createByMaster(manicure(anna, earlierSlot), false, master);
        var agreedStart = earlierSlot.startsAt().minus(Duration.ofHours(4));
        offers.propose(earlier.id(), agreedStart, master);
        offers.respond(earlier.id(), true, Actor.client(anna));
        var cancelledLater = bookings.createByMaster(manicure(anna, slot(TUESDAY, "15:00")), false, master);
        // Запись другого клиента и отменённая после обезличивания в список не попадают.
        book(client("Мария"), slot(TUESDAY, "18:00"));

        clients.erase(anna);
        bookings.cancel(cancelledLater.id(), master);

        // Визит «раньше» начинается в 14:00 по согласию — раньше записи на 15:00.
        assertThat(queries.underReview(master))
                .extracting(BookingView::id)
                .containsExactly(earlier.id(), monday.id(), tuesday.id());
    }

    /** Граница — как у «визит начался»: визит, начинающийся ровно сейчас, уже ждёт отметки, а не решения. */
    @Test
    void underReviewEndsWhenVisitStarts() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var atSlot = bookings.createByMaster(manicure(anna, slot), false, master);
        var laterSlot = slot(MONDAY, "18:00");
        var atAgreedStart = bookings.createByMaster(manicure(anna, laterSlot), false, master);
        offers.propose(atAgreedStart.id(), slot.startsAt(), master);
        offers.respond(atAgreedStart.id(), true, Actor.client(anna));
        clients.erase(anna);

        clock.set(slot.startsAt().minusSeconds(1));
        assertThat(queries.underReview(master))
                .extracting(BookingView::id)
                .containsExactlyInAnyOrder(atSlot.id(), atAgreedStart.id());

        // Ровно в начале: и визит по слоту, и визит по согласованному раннему времени (слот ещё впереди) начались.
        clock.set(slot.startsAt());
        assertThat(queries.underReview(master)).isEmpty();
        assertThat(queries.awaitingMark(master)).extracting(BookingView::id).contains(atSlot.id());
    }

    @Test
    void eraseWithdrawsPendingEarlierTimeOffer() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);

        clients.erase(anna);

        var stored = reloaded(booking);
        assertThat(stored.earlierTime()).isNotNull();
        assertThat(stored.earlierTime().status()).isEqualTo(EarlierTimeStatus.WITHDRAWN);
        assertThat(stored.agreedStart()).isNull();
    }

    /** Сообщить клиентке нельзя, её текст заново не пишется: мастеру остаётся оставить запись или отменить. */
    @Test
    void masterCannotRescheduleOfferEarlierTimeOrEditCommentOfBookingUnderReview() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var otherSlot = slot(TUESDAY, "12:00");
        clients.erase(anna);

        assertThatThrownBy(() -> bookings.reschedule(booking.id(), otherSlot.id(), master))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> offers.propose(booking.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master))
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> bookings.updateComment(booking.id(), "Придёт с подругой", master))
                .isInstanceOf(InvalidTransitionException.class);

        var stored = reloaded(booking);
        assertThat(stored.slotId()).isEqualTo(slot.id());
        assertThat(stored.earlierTime()).isNull();
        assertThat(stored.comment()).isNull();
        assertThat(queries.underReview(master)).extracting(BookingView::id).containsExactly(booking.id());
    }

    @Test
    void confirmWithoutPrepaymentLeavesErasedCardUntouched() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        clients.erase(anna);
        // Признак обезличивание не снимает: прежний код попытался бы снять его с обезличенной карточки и упал бы.
        assertThat(owesPrepayment(anna)).isTrue();
        var flagHistory = clientQueries.prepaymentHistory(anna, master).size();

        var confirmed = prepayments.confirmWithoutPrepayment(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.prepaymentReceivedAt()).isNull();
        assertThat(owesPrepayment(anna)).isTrue();
        assertThat(clientQueries.prepaymentHistory(anna, master)).hasSize(flagHistory);
        assertThat(queries.underReview(master)).extracting(BookingView::id).containsExactly(booking.id());
    }

    @Test
    void underReviewNeedsBookingsRead() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        clients.erase(anna);
        var reader = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));

        assertThat(queries.underReview(reader)).extracting(BookingView::id).containsExactly(booking.id());
        assertThat(queries.underReview(Actor.developer()))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
        assertThatThrownBy(() -> queries.underReview(Actor.client(anna))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.underReview(Actor.of(null, null, Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void masterCancelOfErasedClientBookingUsesAccountDeleted() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = received(anna, slot);
        clients.erase(anna);
        var flagHistory = clientQueries.prepaymentHistory(anna, master).size();

        var cancelled = bookings.cancel(booking.id(), master);

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
        assertThat(cancelled.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
        assertThat(scheduleQueries.freeSlots(MONDAY, MONDAY))
                .extracting(SlotView::id)
                .containsExactly(slot.id());
        assertThat(clientQueries.prepaymentHistory(anna, master)).hasSize(flagHistory);
        assertThat(queries.underReview(master)).isEmpty();

        clock.advance(Duration.ofDays(7));
        assertThat(prepayments
                        .setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master)
                        .prepaymentOutcome())
                .isEqualTo(PrepaymentOutcome.RETURNED);
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);
    }

    @Test
    void prepaymentTimeoutStillCancelsAwaitingBooking() {
        var anna = client("Анна");
        var booking = bookings.createByMaster(manicure(anna, slot(MONDAY, "12:00")), true, master);
        clients.erase(anna);
        var deadline = booking.prepaymentDeadline();
        assertThat(deadline).isNotNull();
        clock.set(deadline.plusSeconds(1));

        assertThat(maintenance.cancelOverduePrepayments()).isEqualTo(1);

        var cancelled = reloaded(booking);
        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.PREPAYMENT_TIMEOUT);
        assertThat(cancelled.clientErasedAt()).isNotNull();
        assertThat(queries.underReview(master)).isEmpty();
    }

    @Test
    void receivedPrepaymentCanBeMarkedAfterErase() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        clients.erase(anna);

        var confirmed = prepayments.markReceived(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.prepaymentAmount()).isEqualByComparingTo("1250.00");
        assertThat(queries.underReview(master)).extracting(BookingView::id).containsExactly(booking.id());
    }

    @Test
    void erasedBookingAwaitsMarkAfterVisitStart() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = received(anna, slot);
        clients.erase(anna);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        assertThat(queries.awaitingMark(master)).extracting(BookingView::id).containsExactly(booking.id());

        var noShow = marks.markNoShow(booking.id(), master);
        assertThat(noShow.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(noShow.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
        assertThat(queries.underReview(master)).isEmpty();
    }

    /** Запись клиента с признаком предоплаты, предоплата получена (по умолчанию — 50 % от 2500). */
    private BookingView received(UUID clientId, SlotView slot) {
        var booking = book(clientId, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        return prepayments.markReceived(booking.id(), master);
    }

    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
