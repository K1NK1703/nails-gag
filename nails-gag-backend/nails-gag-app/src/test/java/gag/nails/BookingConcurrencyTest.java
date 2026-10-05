package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingQueries;
import gag.nails.booking.BookingService;
import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.booking.CancelReason;
import gag.nails.booking.CreateBooking;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.clients.ClientService;
import gag.nails.clients.ConsentKind;
import gag.nails.clients.ConsentSource;
import gag.nails.clients.CreateClient;
import gag.nails.clients.RecordConsent;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotOccupiedException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/** Сценарий 8 спецификации: параллельные действия дают согласованный итог (раздел 7.6). */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class BookingConcurrencyTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");
    private static final String DOCUMENTS_VERSION = "2026-09-25";
    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    BookingService bookings;

    @Autowired
    BookingQueries bookingQueries;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries scheduleQueries;

    @Autowired
    Clock clock;

    private final Actor master = Actor.master();

    @Test
    void twoClientsRacingForOneSlotGetExactlyOneBooking() throws Exception {
        var day = daysFromNow(3);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");
        var maria = client("Мария");

        var outcomes = race(() -> book(anna, slot), () -> book(maria, slot));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::failure)
                .isInstanceOf(SlotOccupiedException.class);
        assertThat(scheduleQueries.slotsOfDay(day, master)).singleElement().matches(SlotView::occupied);
        assertThat(bookingQueries.ofDay(day, master)).hasSize(1);
    }

    @Test
    void oneClientRacingForTwoSlotsStaysWithinTheLimit() throws Exception {
        var first = schedule.addSlot(daysFromNow(4), NOON, master);
        var second = schedule.addSlot(daysFromNow(5), NOON, master);
        var anna = client("Анна");

        var outcomes = race(() -> book(anna, first), () -> book(anna, second));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::failure)
                .isInstanceOf(LimitReachedException.class);
        assertThat(bookingQueries.activeOfClient(anna, master)).hasSize(1);
    }

    @Test
    void masterClosingSlotAndClientBookingItNeverBothSucceed() throws Exception {
        var day = daysFromNow(6);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");

        var outcomes = race(() -> book(anna, slot), () -> {
            schedule.deleteSlot(slot.id(), master);
            return slot.id();
        });

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        var slotsLeft = scheduleQueries.slotsOfDay(day, master);
        if (bookingQueries.ofDay(day, master).isEmpty()) {
            assertThat(slotsLeft).isEmpty();
        } else {
            assertThat(slotsLeft).singleElement().matches(SlotView::occupied);
        }
    }

    @Test
    void bookingCancelledTwiceAtOnceIsCancelledOnce() throws Exception {
        var day = daysFromNow(7);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");
        var booking = book(anna, slot);

        var outcomes = race(
                () -> bookings.cancel(booking.id(), Actor.client(anna)),
                () -> bookings.cancel(booking.id(), Actor.client(anna)));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::failure)
                .isInstanceOf(InvalidTransitionException.class);
        var cancelled = bookingQueries.get(booking.id(), master);
        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(bookingQueries.activeOfClient(anna, master)).isEmpty();
        assertThat(bookingQueries.historyOfClient(anna, master))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
        assertThat(scheduleQueries.slotsOfDay(day, master))
                .singleElement()
                .matches(free -> free.id().equals(slot.id()) && !free.occupied());
    }

    @Test
    void prepaymentReceivedRacingWithClientCancelLeavesBookingAndSlotConsistent() throws Exception {
        var day = daysFromNow(8);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");
        clients.setPrepaymentRequired(anna, true, null, master);
        var booking = book(anna, slot);
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);

        var outcomes = race(
                () -> prepayments.markReceived(booking.id(), master),
                () -> bookings.cancel(booking.id(), Actor.client(anna)));

        // Клиент вправе отменить запись и до, и после отметки предоплаты, поэтому отмена проходит всегда;
        // отметка либо успела раньше, либо получила отказ по состоянию записи — но не внутреннюю ошибку.
        var received = outcomes.get(0);
        var cancellation = outcomes.get(1);
        assertThat(cancellation.failure()).isNull();
        if (!received.succeeded()) {
            assertThat(received.failure()).isInstanceOf(InvalidTransitionException.class);
        }
        var result = bookingQueries.get(booking.id(), master);
        assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.cancelReason()).isEqualTo(CancelReason.CLIENT);
        assertThat(result.prepaymentDeadline()).isNull();
        // Обновление не потеряно: отметка предоплаты осталась на записи ровно тогда, когда она прошла.
        assertThat(result.prepaymentReceivedAt() != null).isEqualTo(received.succeeded());
        assertThat(scheduleQueries.slotsOfDay(day, master))
                .singleElement()
                .matches(free -> free.id().equals(slot.id()) && !free.occupied());
        assertThat(bookingQueries.activeOfClient(anna, master)).isEmpty();
    }

    private LocalDate daysFromNow(int days) {
        return LocalDate.now(clock).plusDays(days);
    }

    private UUID client(String firstName) {
        var id = clients.create(new CreateClient(firstName, null, null, null, null, null), master)
                .id();
        for (var kind : List.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)) {
            clients.recordConsent(
                    id, new RecordConsent(kind, DOCUMENTS_VERSION, ConsentSource.ENTERED_BY_MASTER), master);
        }
        return id;
    }

    private BookingView book(UUID clientId, SlotView slot) {
        return bookings.createByClient(
                new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), Actor.client(clientId));
    }

    /** Запускает попытки одновременно и возвращает исход каждой. */
    private static List<Outcome> race(Callable<?>... attempts) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts.length)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (var attempt : attempts) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        attempt.call();
                        return new Outcome(null);
                    } catch (Exception e) {
                        return new Outcome(e);
                    }
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private record Outcome(@Nullable Exception failure) {

        boolean succeeded() {
            return failure == null;
        }
    }
}
