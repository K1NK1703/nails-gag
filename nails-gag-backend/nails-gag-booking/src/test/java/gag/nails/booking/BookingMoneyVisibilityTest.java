package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.common.actor.Actor;
import gag.nails.testsupport.ModuleTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Клиент денег на сайте не видит: в его записях — и в ответах на его действия — итог визита, причина суммы, сумма и
 * исход предоплаты пусты. Сотрудник с чтением «Записей» видит их.
 */
@ModuleTest
class BookingMoneyVisibilityTest extends BookingTestSupport {

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    VisitMarks marks;

    @Autowired
    EarlierTimeOffers offers;

    @Test
    void clientDoesNotSeePrepaymentInOwnBooking() {
        var anna = clientOwingPrepayment("Анна");
        var asAnna = Actor.client(anna);
        var slot = slot(MONDAY, "12:00");
        var created = book(anna, slot);
        assertNoMoney(created);
        assertThat(prepayments.markReceived(created.id(), master).prepaymentAmount())
                .isEqualByComparingTo("1250.00");

        assertNoMoney(queries.get(created.id(), asAnna));
        assertThat(queries.activeOfClient(anna, asAnna)).singleElement().satisfies(this::assertNoMoney);
        assertNoMoney(bookings.updateComment(created.id(), "Приду с подругой", asAnna));
        offers.propose(created.id(), slot.startsAt().minus(Duration.ofMinutes(90)), master);
        assertNoMoney(offers.respond(created.id(), false, asAnna));
        assertNoMoney(bookings.reschedule(created.id(), slot(TUESDAY, "12:00").id(), asAnna));

        for (var staff : List.of(master, Actor.developer())) {
            var view = queries.get(created.id(), staff);
            assertThat(view.prepaymentAmount()).isEqualByComparingTo("1250.00");
            assertThat(view.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
        }

        var cancelled = bookings.cancel(created.id(), asAnna);
        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertNoMoney(cancelled);
        assertThat(queries.historyOfClient(anna, asAnna)).singleElement().satisfies(this::assertNoMoney);
        assertThat(queries.get(created.id(), master).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);
    }

    @Test
    void clientDoesNotSeeVisitAmountAndNote() {
        var maria = client("Мария");
        var asMaria = Actor.client(maria);
        var slot = slot(MONDAY, "12:00");
        var booking = book(maria, slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        marks.updateVisit(
                booking.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "скидка"), master);

        var own = queries.get(booking.id(), asMaria);
        assertNoMoney(own);
        assertThat(own.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(own.actualServices()).hasSize(1);
        assertThat(queries.historyOfClient(maria, asMaria)).singleElement().satisfies(this::assertNoMoney);

        var seenByMaster = queries.historyOfClient(maria, master).getFirst();
        assertThat(seenByMaster.finalAmount()).isEqualByComparingTo("2000.00");
        assertThat(seenByMaster.amountNote()).isEqualTo("скидка");
    }

    private void assertNoMoney(BookingView view) {
        assertThat(view.finalAmount()).isNull();
        assertThat(view.amountNote()).isNull();
        assertThat(view.prepaymentAmount()).isNull();
        assertThat(view.prepaymentOutcome()).isNull();
    }
}
