package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Исход полученной предоплаты и его переопределение: спецификация финансов §3.3. */
@ModuleTest
class PrepaymentOutcomeTest extends BookingTestSupport {

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    VisitMarks marks;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    /**
     * Чем закончилась запись с полученной предоплатой и какой исход у предоплаты по умолчанию (таблица спецификации
     * §3.3). Запись клиента, удалившего аккаунт, отменяет мастер — с причиной удаления аккаунта, предоплата
     * удерживается. Отмена по сроку предоплаты с полученной предоплатой невозможна.
     */
    enum Ending {
        CANCELLED_BY_CLIENT_IN_TIME(CancelReason.CLIENT, PrepaymentOutcome.RETURNED),
        CANCELLED_BY_CLIENT_LATE(CancelReason.CLIENT_LATE, PrepaymentOutcome.KEPT),
        CANCELLED_BY_MASTER(CancelReason.MASTER, PrepaymentOutcome.RETURNED),
        REFUSED_FOR_LATE_ARRIVAL(CancelReason.LATE_ARRIVAL, PrepaymentOutcome.KEPT),
        CANCELLED_AFTER_ACCOUNT_DELETION(CancelReason.ACCOUNT_DELETED, PrepaymentOutcome.KEPT),
        NO_SHOW(null, PrepaymentOutcome.KEPT);

        final @Nullable CancelReason reason;
        final PrepaymentOutcome outcome;

        Ending(@Nullable CancelReason reason, PrepaymentOutcome outcome) {
            this.reason = reason;
            this.outcome = outcome;
        }
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void outcomeFollowsHowBookingEnded(Ending ending) {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = received(anna, slot);

        end(ending, booking, anna, slot);

        // Исход видит сотрудник; клиенту он не отдаётся (BookingMoneyVisibilityTest).
        var ended = queries.get(booking.id(), master);
        assertThat(ended.cancelReason()).isEqualTo(ending.reason);
        assertThat(ended.prepaymentOutcome()).isEqualTo(ending.outcome);
        var stored = reloaded(booking);
        assertThat(stored.prepaymentOutcome()).isEqualTo(ending.outcome);
        assertThat(stored.prepaymentAmount()).isEqualByComparingTo("1250.00");
    }

    @Test
    void completedVisitApplies() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = received(anna, slot);
        assertThat(booking.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
        timeIsAfterStartOf(slot, Duration.ofHours(2));

        var completed = marks.complete(booking.id(), master);

        assertThat(completed.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
    }

    @Test
    void bookingWithoutPrepaymentHasNoOutcome() {
        var slot = slot(MONDAY, "12:00");
        var booking = book(client("Анна"), slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));

        assertThat(marks.markNoShow(booking.id(), master).prepaymentOutcome()).isNull();
    }

    @Test
    void overrideKeptToReturnedAndBack() {
        var booking = noShowWithPrepayment();

        var returned = prepayments.setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master);
        assertThat(returned.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);

        var kept = prepayments.setOutcome(booking.id(), PrepaymentOutcome.KEPT, master);
        assertThat(kept.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
    }

    /** Значение, совпадающее с правилом, хранится как отсутствие переопределения: исход снова следует правилу. */
    @Test
    void overrideEqualToRuleResetsIt() {
        var anna = clientOwingPrepayment("Анна");
        var booking = received(anna, slot(MONDAY, "12:00"));
        bookings.cancel(booking.id(), master);

        prepayments.setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master);
        assertThat(storedOverride(booking)).isNull();

        prepayments.setOutcome(booking.id(), PrepaymentOutcome.KEPT, master);
        assertThat(storedOverride(booking)).isEqualTo("KEPT");

        var reset = prepayments.setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master);
        assertThat(reset.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);
        assertThat(storedOverride(booking)).isNull();
    }

    /** Срок считается от неявки; ровно на границе — можно. */
    @Test
    void overrideOutsideWindowIsRejected() {
        var noShow = noShowWithPrepayment();
        var noShowAt = noShow.noShowAt();
        assertThat(noShowAt).isNotNull();
        clock.set(noShowAt.plus(Duration.ofDays(7)));
        assertThat(prepayments
                        .setOutcome(noShow.id(), PrepaymentOutcome.RETURNED, master)
                        .prepaymentOutcome())
                .isEqualTo(PrepaymentOutcome.RETURNED);
        clock.set(noShowAt.plus(Duration.ofDays(7)).plusSeconds(1));
        assertThatThrownBy(() -> prepayments.setOutcome(noShow.id(), PrepaymentOutcome.KEPT, master))
                .isInstanceOf(CorrectionWindowClosedException.class)
                .satisfies(e -> assertThat(((CorrectionWindowClosedException) e).params())
                        .containsEntry("windowDays", 7));
    }

    /** Отмена за пять дней до слота: через семь дней после отмены слот ещё в сроке, а отмена — уже нет. */
    @Test
    void windowCountsFromCancellationNotFromSlot() {
        var slot = slot(TUESDAY, "12:00");
        var booking = received(clientOwingPrepayment("Мария"), slot);
        var cancelledAt = bookings.cancel(booking.id(), master).cancelledAt();
        assertThat(cancelledAt).isNotNull();

        clock.set(cancelledAt.plus(Duration.ofDays(7)));
        assertThat(prepayments
                        .setOutcome(booking.id(), PrepaymentOutcome.KEPT, master)
                        .prepaymentOutcome())
                .isEqualTo(PrepaymentOutcome.KEPT);

        clock.set(cancelledAt.plus(Duration.ofDays(7)).plusSeconds(1));
        assertThat(slot.startsAt().plus(Duration.ofDays(7))).isAfter(clock.instant());
        assertThatThrownBy(() -> prepayments.setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master))
                .isInstanceOf(CorrectionWindowClosedException.class);
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
    }

    @Test
    void appliedCannotBeSet() {
        var booking = noShowWithPrepayment();

        assertThatThrownBy(() -> prepayments.setOutcome(booking.id(), PrepaymentOutcome.APPLIED, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("outcome"));
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
    }

    @Test
    void overrideNeedsReceivedPrepayment() {
        var withoutPrepayment = book(client("Анна"), slot(MONDAY, "10:00"));
        bookings.cancel(withoutPrepayment.id(), master);
        assertThatThrownBy(() -> prepayments.setOutcome(withoutPrepayment.id(), PrepaymentOutcome.KEPT, master))
                .isInstanceOf(InvalidTransitionException.class);

        // Предоплата получена, но запись активна или выполнена: исход — в счёт визита, переопределять нечего.
        var slot = slot(MONDAY, "14:00");
        var active = received(clientOwingPrepayment("Мария"), slot);
        assertThatThrownBy(() -> prepayments.setOutcome(active.id(), PrepaymentOutcome.KEPT, master))
                .isInstanceOf(InvalidTransitionException.class);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(active.id(), master);
        assertThatThrownBy(() -> prepayments.setOutcome(active.id(), PrepaymentOutcome.RETURNED, master))
                .isInstanceOf(InvalidTransitionException.class);

        assertThatThrownBy(() -> prepayments.setOutcome(UUID.randomUUID(), PrepaymentOutcome.KEPT, master))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void overrideNeedsBookingsWrite() {
        var booking = noShowWithPrepayment();

        assertThatThrownBy(() -> prepayments.setOutcome(
                        booking.id(), PrepaymentOutcome.RETURNED, Actor.client(booking.clientId())))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Исправление неявки на «выполнена» снимает переопределение: у выполненного визита исход — в счёт визита. */
    @Test
    void correctionToCompletedDropsOverride() {
        var booking = noShowWithPrepayment();
        prepayments.setOutcome(booking.id(), PrepaymentOutcome.RETURNED, master);
        clock.advance(Duration.ofMinutes(10));

        assertThat(marks.complete(booking.id(), master).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
        assertThat(storedOverride(booking)).isNull();

        clock.advance(Duration.ofMinutes(10));
        assertThat(marks.markNoShow(booking.id(), master).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
    }

    /** Запись клиента с признаком предоплаты, предоплата получена (по умолчанию — 50 % от 2500). */
    private BookingView received(UUID clientId, SlotView slot) {
        var booking = book(clientId, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        return prepayments.markReceived(booking.id(), master);
    }

    private BookingView noShowWithPrepayment() {
        var anna = clientOwingPrepayment("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = received(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofMinutes(30));
        var missed = marks.markNoShow(booking.id(), master);
        assertThat(missed.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
        return missed;
    }

    private BookingView end(Ending ending, BookingView booking, UUID clientId, SlotView slot) {
        return switch (ending) {
            case CANCELLED_BY_CLIENT_IN_TIME -> bookings.cancel(booking.id(), Actor.client(clientId));
            case CANCELLED_BY_CLIENT_LATE -> {
                timeIsBefore(slot, Duration.ofHours(2));
                yield bookings.cancel(booking.id(), Actor.client(clientId));
            }
            case CANCELLED_BY_MASTER -> bookings.cancel(booking.id(), master);
            case REFUSED_FOR_LATE_ARRIVAL -> {
                timeIsAfterStartOf(slot, Duration.ofMinutes(30));
                yield bookings.refuseLateArrival(booking.id(), master);
            }
            case CANCELLED_AFTER_ACCOUNT_DELETION -> {
                // Обезличивание запись не отменяет; отменяет мастер, и причина — удаление аккаунта.
                clients.erase(clientId);
                yield bookings.cancel(booking.id(), master);
            }
            case NO_SHOW -> {
                timeIsAfterStartOf(slot, Duration.ofMinutes(30));
                yield marks.markNoShow(booking.id(), master);
            }
        };
    }

    private @Nullable String storedOverride(BookingView booking) {
        entityManager.flush();
        var values = jdbc.sql("select prepayment_outcome_override from booking.booking where id = :id")
                .param("id", booking.id())
                .query(String.class)
                .list();
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
