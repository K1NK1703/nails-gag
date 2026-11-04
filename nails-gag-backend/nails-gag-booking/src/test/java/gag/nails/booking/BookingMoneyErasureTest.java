package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.clients.command.RegisterClient;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Денежные поля записи при обезличивании и объединении клиента. Причина суммы — свободный текст, где может оказаться
 * имя, поэтому обезличивание её стирает, как комментарий клиента; суммы — не персональные данные и остаются.
 * Объединение денежные поля не трогает.
 */
@ModuleTest
class BookingMoneyErasureTest extends BookingTestSupport {

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void eraseWipesAmountNoteButKeepsAmounts() {
        var anna = clientOwingPrepayment("Анна");
        var visit = completedWithNote(anna, "Подарок от Анны");

        clients.erase(anna);

        var kept = reloaded(visit);
        assertThat(kept.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(kept.amountNote()).isNull();
        assertThat(kept.finalAmount()).isEqualByComparingTo("1000.00");
        assertThat(kept.prepaymentAmount()).isEqualByComparingTo("1250.00");
        assertThat(kept.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
    }

    @Test
    void mergeKeepsAmountNoteAndAmounts() {
        var card = clientOwingPrepayment("Мария");
        var visit = completedWithNote(card, "переделка");
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, null))
                .id();

        clients.merge(card, registered, master);

        var moved = reloaded(visit);
        assertThat(moved.clientId()).isEqualTo(registered);
        assertThat(moved.amountNote()).isEqualTo("переделка");
        assertThat(moved.finalAmount()).isEqualByComparingTo("1000.00");
        assertThat(moved.prepaymentAmount()).isEqualByComparingTo("1250.00");
    }

    /** Предоплата получена (50 % от 2500), визит выполнен с итогом 1000 и причиной суммы. */
    private BookingView completedWithNote(UUID clientId, String note) {
        var slot = slot(MONDAY, "12:00");
        var booking = book(clientId, slot);
        prepayments.markReceived(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        return marks.updateVisit(
                booking.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("1000.00"), note), master);
    }

    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
