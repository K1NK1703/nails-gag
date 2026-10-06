package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingMaintenance;
import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingQueries;
import gag.nails.booking.BookingService;
import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.booking.CancelReason;
import gag.nails.booking.CreateBooking;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.ConsentKind;
import gag.nails.clients.ConsentSource;
import gag.nails.clients.CreateClient;
import gag.nails.clients.RecordConsent;
import gag.nails.clients.RegisterClient;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

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
    ClientQueries clientQueries;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries scheduleQueries;

    @Autowired
    BookingMaintenance maintenance;

    @Autowired
    ShiftedClock clock;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.master();

    @AfterEach
    void backToRealTime() {
        clock.reset();
    }

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
        var booked = outcomes.get(0);
        var closed = outcomes.get(1);
        if (!booked.succeeded()) {
            assertThat(booked.failure()).isInstanceOfAny(SlotOccupiedException.class, SlotNotFoundException.class);
        }
        if (!closed.succeeded()) {
            assertThat(closed.failure()).isInstanceOf(SlotOccupiedException.class);
        }
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

    @Test
    void mergeRacingWithMasterCancelKeepsBookingCancelledAndMovesItToSurvivor() throws Exception {
        var day = daysFromNow(9);
        var slot = schedule.addSlot(day, NOON, master);
        var card = client("Мария");
        var registered = registeredClient();
        var booking = book(card, slot);

        var outcomes = race(() -> clients.merge(card, registered, master), () -> bookings.cancel(booking.id(), master));

        assertThat(outcomes).extracting(Outcome::failure).containsOnlyNulls();
        var result = bookingQueries.get(booking.id(), master);
        assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.cancelReason()).isEqualTo(CancelReason.MASTER);
        assertThat(result.clientId()).isEqualTo(registered);
        assertThat(bookingQueries.activeOfClient(registered, master)).isEmpty();
        assertThat(bookingQueries.historyOfClient(registered, master))
                .extracting(BookingView::id)
                .containsExactly(booking.id());
        assertNoBookingsOf(card);
        assertThat(scheduleQueries.slotsOfDay(day, master))
                .singleElement()
                .matches(free -> free.id().equals(slot.id()) && !free.occupied());
    }

    @Test
    void mergeRacingWithMasterBookingNeverLeavesBookingOnRemovedClient() throws Exception {
        var earlierDay = daysFromNow(10);
        var day = daysFromNow(11);
        var slot = schedule.addSlot(day, NOON, master);
        var card = client("Мария");
        var registered = registeredClient();
        // Прежняя запись: у карточки уже есть строка блокировки клиента, которую объединение удалит.
        var earlier = book(card, schedule.addSlot(earlierDay, NOON, master));
        bookings.cancel(earlier.id(), master);

        var outcomes = race(
                () -> clients.merge(card, registered, master),
                () -> bookings.createByMaster(
                        new CreateBooking(card, slot.id(), MANICURE, List.of(), null), false, master));

        var merged = outcomes.get(0);
        var created = outcomes.get(1);
        assertThat(merged.failure()).isNull();
        var slotAfter = scheduleQueries.slotsOfDay(day, master);
        if (created.succeeded()) {
            assertThat(bookingQueries.ofDay(day, master)).singleElement().satisfies(booking -> {
                assertThat(booking.clientId()).isEqualTo(registered);
                assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
            });
            assertThat(slotAfter).singleElement().matches(SlotView::occupied);
        } else {
            assertThat(created.failure()).isInstanceOf(ClientNotFoundException.class);
            assertThat(bookingQueries.ofDay(day, master)).isEmpty();
            assertThat(slotAfter).singleElement().matches(free -> !free.occupied());
        }
        assertThat(bookingQueries.historyOfClient(registered, master))
                .extracting(BookingView::id)
                .containsExactly(earlier.id());
        assertNoBookingsOf(card);
    }

    /**
     * Не гонка, а заданный порядок: запись мастером ждёт блокировку клиента, которую держит ещё не
     * зафиксированное объединение, и после фиксации обнаруживает, что строки блокировки и клиента нет.
     */
    @Test
    void masterBookingThatWaitedForMergeGetsClientNotFound() throws Exception {
        var day = daysFromNow(14);
        var slot = schedule.addSlot(day, NOON, master);
        var card = client("Мария");
        var registered = registeredClient();
        var earlier = book(card, schedule.addSlot(daysFromNow(13), NOON, master));
        bookings.cancel(earlier.id(), master);

        Outcome created;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var waiting = transactions.execute(status -> {
                clients.merge(card, registered, master);
                var attempt = executor.submit(() -> {
                    try {
                        bookings.createByMaster(
                                new CreateBooking(card, slot.id(), MANICURE, List.of(), null), false, master);
                        return new Outcome(null);
                    } catch (Exception e) {
                        return new Outcome(e);
                    }
                });
                assertThat(anotherTransactionWaitsForLock()).isTrue();
                return attempt;
            });
            created = waiting.get(30, TimeUnit.SECONDS);
        }

        assertThat(created.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(bookingQueries.ofDay(day, master)).isEmpty();
        assertThat(scheduleQueries.slotsOfDay(day, master)).singleElement().matches(free -> !free.occupied());
        assertNoBookingsOf(card);
    }

    @Test
    void overduePrepaymentJobRacingWithPrepaymentReceivedLeavesBookingAndSlotConsistent() throws Exception {
        var day = daysFromNow(12);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");
        clients.setPrepaymentRequired(anna, true, null, master);
        var booking = book(anna, slot);
        var deadline = booking.prepaymentDeadline();
        assertThat(deadline).isNotNull();
        clock.moveTo(deadline.plusSeconds(60));

        var outcomes = race(
                () -> maintenance.cancelOverduePrepayments(), () -> prepayments.markReceived(booking.id(), master));

        var job = outcomes.get(0);
        var received = outcomes.get(1);
        assertThat(job.failure()).isNull();
        var result = bookingQueries.get(booking.id(), master);
        var slotAfter = scheduleQueries.slotsOfDay(day, master);
        assertThat(result.prepaymentDeadline()).isNull();
        if (received.succeeded()) {
            assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(result.prepaymentReceivedAt()).isNotNull();
            assertThat(result.cancelReason()).isNull();
            assertThat(slotAfter).singleElement().matches(SlotView::occupied);
        } else {
            assertThat(received.failure()).isInstanceOf(InvalidTransitionException.class);
            assertThat(result.status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(result.cancelReason()).isEqualTo(CancelReason.PREPAYMENT_TIMEOUT);
            assertThat(result.prepaymentReceivedAt()).isNull();
            assertThat(slotAfter).singleElement().matches(free -> !free.occupied());
        }
    }

    @Test
    void eraseRacingWithClientBookingNeverLeavesActiveBookingOfErasedClient() throws Exception {
        var day = daysFromNow(15);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");

        var outcomes = race(
                () -> {
                    clients.erase(anna);
                    return anna;
                },
                () -> book(anna, slot));

        // Запись либо успела раньше и отменена обезличиванием, либо дождалась его и получила отказ по карточке.
        var erased = outcomes.get(0);
        var created = outcomes.get(1);
        assertThat(erased.failure()).isNull();
        assertThat(clientQueries.get(anna, master).erased()).isTrue();
        assertThat(bookingQueries.activeOfClient(anna, master)).isEmpty();
        assertThat(scheduleQueries.slotsOfDay(day, master))
                .singleElement()
                .matches(free -> free.id().equals(slot.id()) && !free.occupied());
        if (created.succeeded()) {
            assertThat(bookingQueries.ofDay(day, master)).singleElement().satisfies(booking -> {
                assertThat(booking.clientId()).isEqualTo(anna);
                assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(booking.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
                assertThat(booking.comment()).isNull();
            });
        } else {
            assertThat(created.failure()).isInstanceOf(ClientErasedException.class);
            assertThat(bookingQueries.ofDay(day, master)).isEmpty();
        }
    }

    /**
     * Не гонка, а заданный порядок: самозапись ждёт блокировку клиента, которую держит ещё не
     * зафиксированное обезличивание, и после фиксации видит обезличенную карточку. В гонке выше
     * самозапись почти всегда берёт блокировку первой, поэтому эта ветка проверяется отдельно.
     */
    @Test
    void clientBookingThatWaitedForEraseGetsClientErased() throws Exception {
        var day = daysFromNow(17);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");

        Outcome created;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var waiting = transactions.execute(status -> {
                clients.erase(anna);
                var attempt = executor.submit(() -> {
                    try {
                        book(anna, slot);
                        return new Outcome(null);
                    } catch (Exception e) {
                        return new Outcome(e);
                    }
                });
                assertThat(anotherTransactionWaitsForLock()).isTrue();
                return attempt;
            });
            created = waiting.get(30, TimeUnit.SECONDS);
        }

        assertThat(created.failure()).isInstanceOf(ClientErasedException.class);
        assertThat(clientQueries.get(anna, master).erased()).isTrue();
        assertThat(bookingQueries.ofDay(day, master)).isEmpty();
        assertThat(bookingQueries.activeOfClient(anna, master)).isEmpty();
        assertThat(scheduleQueries.slotsOfDay(day, master)).singleElement().matches(free -> !free.occupied());
    }

    @Test
    void blockRacingWithClientBookingIsConsistent() throws Exception {
        var day = daysFromNow(16);
        var slot = schedule.addSlot(day, NOON, master);
        var anna = client("Анна");

        var outcomes = race(() -> clients.block(anna, "грубит", master), () -> book(anna, slot));

        // Блокировка проходит всегда; запись либо успела раньше неё и остаётся, либо получила отказ.
        var blocked = outcomes.get(0);
        var created = outcomes.get(1);
        assertThat(blocked.failure()).isNull();
        assertThat(clientQueries.get(anna, master).blocked()).isTrue();
        var slotAfter = scheduleQueries.slotsOfDay(day, master);
        if (created.succeeded()) {
            assertThat(bookingQueries.activeOfClient(anna, master))
                    .singleElement()
                    .satisfies(booking -> {
                        assertThat(booking.slotId()).isEqualTo(slot.id());
                        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
                    });
            assertThat(slotAfter).singleElement().matches(SlotView::occupied);
        } else {
            assertThat(created.failure()).isInstanceOf(ClientBlockedException.class);
            assertThat(bookingQueries.activeOfClient(anna, master)).isEmpty();
            assertThat(slotAfter).singleElement().matches(free -> !free.occupied());
        }
    }

    /** Ждёт, пока какая-нибудь другая транзакция не встанет в очередь за блокировкой строки. */
    private boolean anotherTransactionWaitsForLock() {
        var giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < giveUpAt) {
            var waiting = jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")
                    .query(Integer.class)
                    .single();
            if (waiting > 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private void assertNoBookingsOf(UUID removedClientId) {
        assertThat(bookingQueries.activeOfClient(removedClientId, master)).isEmpty();
        assertThat(bookingQueries.historyOfClient(removedClientId, master)).isEmpty();
    }

    private UUID registeredClient() {
        return clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, null))
                .id();
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

    /** Настоящее время со сдвигом: тест с истёкшим сроком переводит часы вперёд, остальные идут по настоящим. */
    static final class ShiftedClock extends Clock {

        private final ZoneId zone;
        private volatile Duration shift = Duration.ZERO;

        ShiftedClock(ZoneId zone) {
            this.zone = zone;
        }

        void moveTo(Instant instant) {
            shift = Duration.between(Instant.now(), instant);
        }

        void reset() {
            shift = Duration.ZERO;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            var clock = new ShiftedClock(zone);
            clock.shift = shift;
            return clock;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(shift);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ShiftedTime {

        @Bean
        @Primary
        ShiftedClock shiftedClock(SalonTimeZone salonTimeZone) {
            return new ShiftedClock(salonTimeZone.zone());
        }
    }
}
