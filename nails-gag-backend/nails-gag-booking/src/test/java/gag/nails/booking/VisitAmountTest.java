package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateActualServices;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingView;
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

/** Итог визита с причиной суммы ({@link VisitMarks#updateVisit}): спецификация финансов §3.2. */
@ModuleTest
class VisitAmountTest extends BookingTestSupport {

    @Autowired
    VisitMarks marks;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void updateVisitStoresNoteAndAmount() {
        var visit = completedVisit("Анна", "12:00");

        var updated = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(DESIGN), new BigDecimal("2000.00"), "подарок"), master);

        assertThat(updated.finalAmount()).isEqualByComparingTo("2000.00");
        assertThat(updated.amountNote()).isEqualTo("подарок");
        assertThat(updated.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, DESIGN);
        assertThat(updated.plannedServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);

        var stored = reloaded(visit);
        assertThat(stored.finalAmount()).isEqualByComparingTo("2000.00");
        assertThat(stored.amountNote()).isEqualTo("подарок");
        assertThat(stored.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, DESIGN);
    }

    @Test
    void zeroAndAboveListPriceAreAllowed() {
        var visit = completedVisit("Анна", "12:00");

        var free = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(FRENCH), BigDecimal.ZERO, "бесплатно"), master);
        assertThat(free.finalAmount()).isEqualByComparingTo("0");
        assertThat(reloaded(visit).finalAmount()).isEqualByComparingTo("0");

        var aboveList = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(FRENCH), new BigDecimal("3500.00"), null), master);
        assertThat(aboveList.finalAmount()).isEqualByComparingTo("3500.00");
        assertThat(aboveList.amountNote()).isNull();
        var stored = reloaded(visit);
        assertThat(stored.finalAmount()).isEqualByComparingTo("3500.00");
        assertThat(stored.amountNote()).isNull();
    }

    @Test
    void negativeAmountIsRejected() {
        var visit = completedVisit("Анна", "12:00");

        assertThatThrownBy(() -> marks.updateVisit(
                        visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("-0.01"), "ошибка"), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("finalAmount"));
        assertThat(reloaded(visit).finalAmount()).isEqualByComparingTo("2800.00");
    }

    /** Длина — в символах (code points), как {@code char_length} в CHECK базы, а не в единицах UTF-16. */
    @Test
    void noteLongerThan200CharactersIsRejected() {
        var visit = completedVisit("Анна", "12:00");

        assertThatThrownBy(() -> marks.updateVisit(
                        visit.id(),
                        new UpdateVisit(MANICURE, List.of(), new BigDecimal("2500.00"), "а".repeat(201)),
                        master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("amountNote"));

        var emoji = "😀".repeat(200);
        var updated = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2500.00"), emoji), master);
        assertThat(updated.amountNote()).isEqualTo(emoji);
        assertThat(reloaded(visit).amountNote()).isEqualTo(emoji);
    }

    /** Пробелы по краям не хранятся и в длину не считаются. */
    @Test
    void noteIsStrippedBeforeLengthCheck() {
        var visit = completedVisit("Анна", "12:00");

        var stripped = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "  подарок "), master);
        assertThat(stripped.amountNote()).isEqualTo("подарок");
        assertThat(reloaded(visit).amountNote()).isEqualTo("подарок");

        var longest = "я".repeat(200);
        var padded = marks.updateVisit(
                visit.id(),
                new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "  " + longest + "\t "),
                master);
        assertThat(padded.amountNote()).isEqualTo(longest);
        assertThat(reloaded(visit).amountNote()).isEqualTo(longest);
    }

    @Test
    void blankNoteMeansNoNote() {
        var visit = completedVisit("Анна", "12:00");
        marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "скидка"), master);

        var updated = marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2500.00"), "   "), master);

        assertThat(updated.amountNote()).isNull();
        assertThat(reloaded(visit).amountNote()).isNull();
    }

    @Test
    void noShowClearsNote() {
        var visit = completedVisit("Анна", "12:00");
        marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("1000.00"), "переделка"), master);
        clock.advance(Duration.ofHours(1));

        var missed = marks.markNoShow(visit.id(), master);

        assertThat(missed.amountNote()).isNull();
        assertThat(missed.finalAmount()).isNull();
        assertThat(reloaded(visit).amountNote()).isNull();

        clock.advance(Duration.ofHours(1));
        var corrected = marks.complete(visit.id(), master);
        assertThat(corrected.amountNote()).isNull();
        assertThat(corrected.finalAmount()).isEqualByComparingTo("2800.00");
    }

    @Test
    void updateActualServicesKeepsNote() {
        var visit = completedVisit("Анна", "12:00");
        marks.updateVisit(
                visit.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "переделка"), master);

        var adjusted = marks.updateActualServices(
                visit.id(), new UpdateActualServices(MANICURE, List.of(DESIGN), new BigDecimal("2100.00")), master);

        assertThat(adjusted.amountNote()).isEqualTo("переделка");
        assertThat(adjusted.finalAmount()).isEqualByComparingTo("2100.00");
        var stored = reloaded(visit);
        assertThat(stored.amountNote()).isEqualTo("переделка");
        assertThat(stored.finalAmount()).isEqualByComparingTo("2100.00");
    }

    @Test
    void updateVisitRespectsCorrectionWindow() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var command = new UpdateVisit(MANICURE, List.of(), new BigDecimal("2000.00"), "скидка");
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        // Не выполненный визит — не по сроку, а по состоянию.
        assertThatThrownBy(() -> marks.updateVisit(booking.id(), command, master))
                .isInstanceOf(InvalidTransitionException.class);

        marks.complete(booking.id(), master);
        timeIsAfterStartOf(slot, Duration.ofDays(7));
        assertThat(marks.updateVisit(booking.id(), command, master).amountNote())
                .isEqualTo("скидка");

        timeIsAfterStartOf(slot, Duration.ofDays(7).plusSeconds(1));
        assertThatThrownBy(() -> marks.updateVisit(
                        booking.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("2500.00"), null), master))
                .isInstanceOf(CorrectionWindowClosedException.class);
        assertThat(reloaded(booking).amountNote()).isEqualTo("скидка");
    }

    @Test
    void updateVisitNeedsBookingsWrite() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        var command = new UpdateVisit(MANICURE, List.of(), BigDecimal.ZERO, "подарок");

        assertThatThrownBy(() -> marks.updateVisit(booking.id(), command, Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(reloaded(booking).amountNote()).isNull();
    }

    /** Выполненный визит: маникюр с френчем за 2800, отмечен через два часа после начала слота. */
    private BookingView completedVisit(String clientName, String time) {
        var clientId = client(clientName);
        SlotView slot = slot(MONDAY, time);
        var booking = bookings.createByClient(
                new CreateBooking(clientId, slot.id(), MANICURE, List.of(FRENCH), null), Actor.client(clientId));
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        return marks.complete(booking.id(), master);
    }

    /** Состояние из базы: ограничения CHECK срабатывают при flush. */
    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
