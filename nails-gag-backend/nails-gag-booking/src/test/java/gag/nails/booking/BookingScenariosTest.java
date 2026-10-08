package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.LateRescheduleNotAllowedException;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Сценарии 1–7 из раздела 10 спецификации: цепочки действий через все модули. */
@ModuleTest
class BookingScenariosTest extends BookingTestSupport {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingMaintenance maintenance;

    /** Сценарий 1: запись → поздняя отмена → признак → следующая запись ждёт предоплату → срок истёк. */
    @Test
    void lateCancellationLeadsToPrepaymentThatExpires() {
        var anna = client("Анна");
        var first = slot(MONDAY, "12:00");
        var booking = book(anna, first);
        timeIsBefore(first, Duration.ofHours(5));
        bookings.cancel(booking.id(), Actor.client(anna));
        assertThat(owesPrepayment(anna)).isTrue();

        var second = slot(FRIDAY, "12:00");
        var next = book(anna, second);
        assertThat(next.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        clock.set(next.prepaymentDeadline().plusSeconds(60));
        assertThat(maintenance.cancelOverduePrepayments()).isEqualTo(1);

        var expired = queries.get(next.id(), master);
        assertThat(expired.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(expired.cancelReason()).isEqualTo(CancelReason.PREPAYMENT_TIMEOUT);
        assertThat(scheduleQueries.freeSlots(FRIDAY, FRIDAY))
                .extracting(SlotView::id)
                .containsExactly(second.id());
        assertThat(owesPrepayment(anna)).isTrue();
    }

    /** Сценарий 2: клиент с признаком записался → предоплата отмечена → визит выполнен → признак снят. */
    @Test
    void prepaidVisitClearsThePrepaymentFlag() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);

        assertThat(prepayments.markReceived(booking.id(), master).status()).isEqualTo(BookingStatus.CONFIRMED);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);

        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(book(anna, slot(FRIDAY, "12:00")).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    /** Сценарий 3: мастер подтвердила без предоплаты → признак снят с причиной MASTER_WAIVED. */
    @Test
    void masterWaivesPrepayment() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));

        var confirmed = prepayments.confirmWithoutPrepayment(booking.id(), master);

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(clientQueries.prepaymentHistory(anna, master))
                .first()
                .extracting(PrepaymentFlagChangeView::reason)
                .isEqualTo(PrepaymentFlagReason.MASTER_WAIVED);
    }

    /** Сценарий 4: перенос клиентом за 3 дня; поздний перенос отклонён; перенос мастером за час прошёл. */
    @Test
    void rescheduleRulesDifferForClientAndMaster() {
        var anna = client("Анна");
        var monday = slot(MONDAY, "12:00");
        var tuesday = slot(TUESDAY, "12:00");
        var friday = slot(FRIDAY, "12:00");
        var booking = book(anna, monday);

        timeIsBefore(monday, Duration.ofDays(3));
        assertThat(bookings.reschedule(booking.id(), tuesday.id(), Actor.client(anna))
                        .slotId())
                .isEqualTo(tuesday.id());

        timeIsBefore(tuesday, Duration.ofHours(5));
        assertThatThrownBy(() -> bookings.reschedule(booking.id(), friday.id(), Actor.client(anna)))
                .isInstanceOf(LateRescheduleNotAllowedException.class);

        timeIsBefore(tuesday, Duration.ofHours(1));
        var moved = bookings.reschedule(booking.id(), friday.id(), master);
        assertThat(moved.slotId()).isEqualTo(friday.id());
        assertThat(moved.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(owesPrepayment(anna)).isFalse();
        assertThat(scheduleQueries.freeSlots(MONDAY, TUESDAY))
                .extracting(SlotView::id)
                .containsExactly(monday.id(), tuesday.id());
    }

    /** Сценарий 5: предложение приехать раньше — принято, отклонено, истекло. */
    @Test
    void earlierTimeIsAcceptedDeclinedOrExpires() {
        var anna = client("Анна");
        var maria = client("Мария");
        var olga = client("Ольга");
        var first = slot(MONDAY, "12:00");
        var second = slot(MONDAY, "15:00");
        var third = slot(MONDAY, "18:00");
        var accepted = book(anna, first);
        var declined = book(maria, second);
        var ignored = book(olga, third);
        var hourEarlier = Duration.ofHours(1);
        offers.propose(accepted.id(), first.startsAt().minus(hourEarlier), master);
        offers.propose(declined.id(), second.startsAt().minus(hourEarlier), master);
        offers.propose(ignored.id(), third.startsAt().minus(hourEarlier), master);

        offers.respond(accepted.id(), true, Actor.client(anna));
        offers.respond(declined.id(), false, Actor.client(maria));
        clock.set(third.startsAt());
        maintenance.expireEarlierTimeOffers();

        assertThat(queries.get(accepted.id(), master).agreedStart())
                .isEqualTo(first.startsAt().minus(hourEarlier));
        assertThat(queries.get(declined.id(), master).earlierTime().status()).isEqualTo(EarlierTimeStatus.DECLINED);
        assertThat(queries.get(declined.id(), master).agreedStart()).isNull();
        assertThat(queries.get(ignored.id(), master).earlierTime().status()).isEqualTo(EarlierTimeStatus.EXPIRED);
    }

    /** Сценарий 6: неявка → признак; исправление на «выполнена» в окне → признак снят. */
    @Test
    void noShowIsCorrectedToCompletedWithinTheWindow() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        marks.markNoShow(booking.id(), master);
        assertThat(owesPrepayment(anna)).isTrue();

        timeIsAfterStartOf(slot, Duration.ofDays(2));
        var corrected = marks.complete(booking.id(), master);

        assertThat(corrected.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(owesPrepayment(anna)).isFalse();
    }

    /** Сценарий 7: объединение клиентов — записи переехали, согласия и история признака перенесены, источник удалён. */
    @Test
    void mergedClientKeepsBookingsConsentsAndPrepaymentHistory() {
        var card = client("Мария");
        var missedSlot = slot(MONDAY, "12:00");
        var missed = book(card, missedSlot);
        timeIsAfterStartOf(missedSlot, Duration.ofMinutes(30));
        marks.markNoShow(missed.id(), master);
        var upcoming = bookings.createByMaster(manicure(card, slot(FRIDAY, "12:00")), false, master);
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, null))
                .id();

        clients.merge(card, registered, master);

        assertThat(queries.activeOfClient(registered, master))
                .extracting(BookingView::id)
                .containsExactly(upcoming.id());
        assertThat(queries.historyOfClient(registered, master))
                .extracting(BookingView::id)
                .containsExactly(missed.id());
        assertThat(clientQueries.hasCurrentConsents(registered, Set.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)))
                .isTrue();
        assertThat(owesPrepayment(registered)).isTrue();
        assertThat(clientQueries.prepaymentHistory(registered, master))
                .extracting(PrepaymentFlagChangeView::reason)
                .contains(PrepaymentFlagReason.NO_SHOW);
        assertThat(clientQueries.byId(card)).isEmpty();
    }
}
