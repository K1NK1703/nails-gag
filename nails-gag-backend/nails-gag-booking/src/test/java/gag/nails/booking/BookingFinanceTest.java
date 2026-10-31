package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.KeptPrepaymentFact;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.booking.model.VisitFact;
import gag.nails.booking.model.VisitServiceFact;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Денежные факты для финансов ({@link BookingFinance}, спецификация финансов §3.4). База общая для тестов, поэтому
 * проверки ищут свои записи среди фактов периода, а не сравнивают весь список. Часы тестов — четверг 1 октября 2026,
 * 09:00 по Москве; слоты — понедельник 5 и вторник 6 октября.
 */
@ModuleTest
class BookingFinanceTest extends BookingTestSupport {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);

    @Autowired
    BookingFinance finance;

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SalonTimeZone salonTimeZone;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor accountant = Actor.of(null, null, Map.of(Section.FINANCE, AccessLevel.READ));

    /**
     * Согласованное раннее время по правилам всегда в тот же день салона, что и слот, поэтому, чтобы день визита по
     * нему отличался от дня слота, строка доводится через базу: согласованное время — накануне вечером (ограничения
     * базы это допускают). День визита — по согласованному времени, а не по слоту.
     */
    @Test
    void visitDayFollowsAgreedStart() {
        var slot = slot(TUESDAY, "10:00");
        var booking = bookings.createByMaster(manicure(client("Анна"), slot), false, master);
        offers.propose(booking.id(), slot.startsAt().minus(Duration.ofHours(1)), master);
        offers.respond(booking.id(), true, Actor.client(booking.clientId()));
        entityManager.flush();
        var eveningBefore = slot.startsAt().minus(Duration.ofHours(14));
        jdbc.sql("update booking.booking set agreed_start = :agreed where id = :id")
                .param("agreed", eveningBefore.atOffset(ZoneOffset.UTC))
                .param("id", booking.id())
                .update();
        entityManager.clear();
        timeIsAfterStartOf(slot, Duration.ofHours(1));
        marks.complete(booking.id(), master);
        entityManager.flush();
        entityManager.clear();

        assertThat(visitIn(MONDAY, MONDAY, booking))
                .isNotNull()
                .extracting(VisitFact::day)
                .isEqualTo(MONDAY);
        assertThat(visitIn(TUESDAY, TUESDAY, booking)).isNull();
    }

    /** 02:30 по Москве во вторник — это 23:30 UTC в понедельник: день визита — вторник. */
    @Test
    void visitDayUsesSalonTimeZone() {
        var slot = slot(TUESDAY, "02:30");
        assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-10-05T23:30:00Z"));
        var booking = completed("Анна", slot);

        assertThat(visitIn(TUESDAY, TUESDAY, booking))
                .isNotNull()
                .extracting(VisitFact::day)
                .isEqualTo(TUESDAY);
        assertThat(visitIn(MONDAY, MONDAY, booking)).isNull();
    }

    @Test
    void periodBoundsAreInclusive() {
        var first = slot(MONDAY, "00:00");
        var last = slot(TUESDAY, "23:30");
        // Записи заведены и отмечены в обратном порядке: факты идут по началу визита, а не по записи.
        var atEnd = completedLater("Мария", last);
        var atStart = completedLater("Анна", first);
        timeIsAfterStartOf(last, Duration.ofHours(1));
        marks.complete(atEnd.id(), master);
        marks.complete(atStart.id(), master);

        assertThat(finance.visits(MONDAY, TUESDAY, accountant))
                .extracting(VisitFact::bookingId)
                .containsSubsequence(atStart.id(), atEnd.id());

        assertThat(visitIn(MONDAY, TUESDAY, atStart)).isNotNull();
        assertThat(visitIn(MONDAY, TUESDAY, atEnd)).isNotNull();
        assertThat(visitIn(MONDAY, MONDAY, atEnd)).isNull();
        assertThat(visitIn(TUESDAY, TUESDAY, atStart)).isNull();
        assertThat(visitIn(SUNDAY, SUNDAY, atStart)).isNull();
        assertThat(visitIn(TUESDAY.plusDays(1), TUESDAY.plusDays(1), atEnd)).isNull();
    }

    @Test
    void factsCarryActualServicesAndAmounts() {
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(
                new CreateBooking(client("Анна"), slot.id(), MANICURE, List.of(FRENCH), "Комментарий клиента"),
                false,
                master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        marks.updateVisit(
                booking.id(), new UpdateVisit(MANICURE, List.of(DESIGN), new BigDecimal("2000.00"), "скидка"), master);

        var fact = visitIn(MONDAY, MONDAY, booking);

        assertThat(fact).isNotNull();
        assertThat(fact.day()).isEqualTo(MONDAY);
        assertThat(fact.services())
                .extracting(VisitServiceFact::serviceId, VisitServiceFact::kind)
                .containsExactly(tuple(MANICURE, ServiceKind.MAIN), tuple(DESIGN, ServiceKind.ADDON));
        assertThat(fact.services())
                .allSatisfy(service -> assertThat(service.title()).isNotBlank());
        assertThat(fact.services())
                .extracting(VisitServiceFact::price)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("2500"), new BigDecimal("200"));
        assertThat(fact.priceSum()).isEqualByComparingTo("2700.00");
        assertThat(fact.finalAmount()).isEqualByComparingTo("2000.00");
        assertThat(fact.amountNote()).isEqualTo("скидка");
    }

    /** Порядок услуг — по порядку в составе, даже если строки в базе лежат в обратном порядке. */
    @Test
    void servicesFollowSortOrder() {
        var slot = slot(MONDAY, "12:00");
        var booking = bookings.createByMaster(
                new CreateBooking(client("Анна"), slot.id(), MANICURE, List.of(FRENCH), null), false, master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(booking.id(), master);
        entityManager.flush();
        // Строки вставлены «маникюр, френч»; порядок в составе меняем на обратный.
        jdbc.sql("""
                        update booking.booking_service set sort_order = 1 - sort_order
                        where booking_id = :id and phase = 'ACTUAL'""").param("id", booking.id()).update();
        entityManager.clear();

        var fact = visitIn(MONDAY, MONDAY, booking);

        assertThat(fact).isNotNull();
        assertThat(fact.services()).extracting(VisitServiceFact::serviceId).containsExactly(FRENCH, MANICURE);
    }

    @Test
    void noShowAndCancelledAreNotVisits() {
        var noShowSlot = slot(MONDAY, "10:00");
        var noShow = bookings.createByMaster(manicure(client("Анна"), noShowSlot), false, master);
        var cancelled = bookings.createByMaster(manicure(client("Мария"), slot(MONDAY, "14:00")), false, master);
        var active = bookings.createByMaster(manicure(client("Ольга"), slot(MONDAY, "18:00")), false, master);
        var visitSlot = slot(MONDAY, "06:00");
        var visit = bookings.createByMaster(manicure(client("Ирина"), visitSlot), false, master);
        bookings.cancel(cancelled.id(), master);
        timeIsAfterStartOf(noShowSlot, Duration.ofHours(1));
        marks.markNoShow(noShow.id(), master);
        marks.complete(visit.id(), master);

        var visits = finance.visits(MONDAY, TUESDAY, accountant);

        assertThat(visits).extracting(VisitFact::bookingId).contains(visit.id());
        assertThat(visits).extracting(VisitFact::bookingId).doesNotContain(noShow.id(), cancelled.id(), active.id());
    }

    /** День удержанной предоплаты — день отмены или неявки, а не день слота. */
    @Test
    void keptPrepaymentsByCancelOrNoShowDay() {
        // Неявку заводим раньше: факты идут по моменту отмены или неявки, а не по записи.
        var noShow = receivedByMaster("Мария", slot(MONDAY, "18:00"));
        var anna = clientOwingPrepayment("Анна");
        var cancelledLate = received(anna, slot(MONDAY, "12:00"));
        // Поздняя отмена клиентом в воскресенье, неявка отмечена во вторник.
        clock.set(salonAt(SUNDAY, 20));
        bookings.cancel(cancelledLate.id(), Actor.client(anna));
        clock.set(salonAt(TUESDAY, 9));
        marks.markNoShow(noShow.id(), master);

        assertThat(keptIn(SUNDAY, SUNDAY, cancelledLate)).isNotNull().satisfies(fact -> {
            assertThat(fact.day()).isEqualTo(SUNDAY);
            assertThat(fact.amount()).isEqualByComparingTo("1250.00");
        });
        assertThat(keptIn(MONDAY, MONDAY, cancelledLate)).isNull();
        assertThat(keptIn(TUESDAY, TUESDAY, noShow))
                .isNotNull()
                .extracting(KeptPrepaymentFact::day)
                .isEqualTo(TUESDAY);
        assertThat(keptIn(MONDAY, MONDAY, noShow)).isNull();
        assertThat(finance.keptPrepayments(SUNDAY, TUESDAY, accountant))
                .extracting(KeptPrepaymentFact::bookingId)
                .containsSubsequence(cancelledLate.id(), noShow.id());
    }

    @Test
    void returnedAndAppliedPrepaymentsAreNotKept() {
        var anna = clientOwingPrepayment("Анна");
        var inTime = received(anna, slot(MONDAY, "10:00"));
        bookings.cancel(inTime.id(), Actor.client(anna));
        var byMaster = receivedByMaster("Мария", slot(MONDAY, "14:00"));
        bookings.cancel(byMaster.id(), master);
        var completedSlot = slot(MONDAY, "18:00");
        var completed = receivedByMaster("Ольга", completedSlot);
        var active = receivedByMaster("Ирина", slot(TUESDAY, "12:00"));
        var noShowSlot = slot(MONDAY, "06:00");
        var noShow = receivedByMaster("Елена", noShowSlot);
        timeIsAfterStartOf(completedSlot, Duration.ofHours(1));
        marks.complete(completed.id(), master);
        marks.markNoShow(noShow.id(), master);

        var kept = finance.keptPrepayments(TODAY, TUESDAY, accountant);

        assertThat(kept).extracting(KeptPrepaymentFact::bookingId).contains(noShow.id());
        assertThat(kept)
                .extracting(KeptPrepaymentFact::bookingId)
                .doesNotContain(inTime.id(), byMaster.id(), completed.id(), active.id());
    }

    @Test
    void overrideIsRespected() {
        var noShowSlot = slot(MONDAY, "10:00");
        var noShow = receivedByMaster("Анна", noShowSlot);
        var cancelled = receivedByMaster("Мария", slot(MONDAY, "14:00"));
        bookings.cancel(cancelled.id(), master);
        timeIsAfterStartOf(noShowSlot, Duration.ofHours(1));
        marks.markNoShow(noShow.id(), master);

        prepayments.setOutcome(noShow.id(), PrepaymentOutcome.RETURNED, master);
        prepayments.setOutcome(cancelled.id(), PrepaymentOutcome.KEPT, master);

        assertThat(keptIn(TODAY, TUESDAY, noShow)).isNull();
        assertThat(keptIn(TODAY, TODAY, cancelled))
                .isNotNull()
                .extracting(KeptPrepaymentFact::day)
                .isEqualTo(TODAY);
    }

    /** Запись клиента, удалившего аккаунт, мастер отменяет с причиной удаления аккаунта: предоплата удержана. */
    @Test
    void keptPrepaymentsIncludeAccountDeletedCancellation() {
        var anna = clientOwingPrepayment("Анна");
        var booking = received(anna, slot(MONDAY, "12:00"));
        clients.erase(anna);
        bookings.cancel(booking.id(), master);

        assertThat(keptIn(TODAY, TODAY, booking))
                .isNotNull()
                .satisfies(fact -> assertThat(fact.amount()).isEqualByComparingTo("1250.00"));
    }

    @Test
    void periodLongerThan366DaysIsRejected() {
        var from = LocalDate.of(2026, 1, 1);
        var longestTo = from.plusDays(365);

        assertThat(finance.visits(from, longestTo, accountant)).isNotNull();
        assertThat(finance.keptPrepayments(from, longestTo, accountant)).isNotNull();
        for (var call : List.<Runnable>of(
                () -> finance.visits(from, longestTo.plusDays(1), accountant),
                () -> finance.keptPrepayments(from, longestTo.plusDays(1), accountant),
                () -> finance.visits(TUESDAY, MONDAY, accountant),
                () -> finance.keptPrepayments(TUESDAY, MONDAY, accountant))) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(ValidationException.class)
                    .satisfies(
                            e -> assertThat(((ValidationException) e).field()).isEqualTo("to"));
        }
    }

    /** Крайние даты — ошибка запроса, а не сбой: проверка идёт до вычисления границ периода. */
    @Test
    void periodDatesAreBounded() {
        var lastDay = TODAY.plusDays(366);
        assertThat(finance.visits(lastDay, lastDay, accountant)).isNotNull();
        assertThat(finance.visits(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 12, 31), accountant))
                .isNotNull();

        for (var period : List.of(
                new LocalDate[] {LocalDate.of(1999, 12, 31), LocalDate.of(2000, 1, 1)},
                new LocalDate[] {LocalDate.MIN, LocalDate.of(2000, 1, 1)})) {
            rejected(period[0], period[1], "from");
        }
        for (var period : List.of(
                new LocalDate[] {lastDay, lastDay.plusDays(1)},
                new LocalDate[] {TODAY, LocalDate.MAX},
                new LocalDate[] {LocalDate.MAX, LocalDate.MAX})) {
            rejected(period[0], period[1], "to");
        }
    }

    @Test
    void needsFinanceRead() {
        var slot = slot(MONDAY, "12:00");
        var anna = client("Анна");
        var booking = completed(anna, slot);

        for (var reader : List.of(master, Actor.developer(), accountant)) {
            assertThat(finance.visits(MONDAY, MONDAY, reader))
                    .extracting(VisitFact::bookingId)
                    .contains(booking.id());
            assertThat(finance.keptPrepayments(MONDAY, MONDAY, reader)).isNotNull();
        }
        var bookingsOnly = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.WRITE));
        for (var stranger : List.of(Actor.client(anna), bookingsOnly)) {
            assertThatThrownBy(() -> finance.visits(MONDAY, MONDAY, stranger))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> finance.keptPrepayments(MONDAY, MONDAY, stranger))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * Число SQL-операторов на вызов не зависит от числа визитов: каждый метод — один запрос. Считает статистика
     * Hibernate ({@code prepareStatementCount}), включённая только на время замера; контекст JPA перед замером
     * очищается, иначе уже загруженный состав записей скрыл бы дозагрузку.
     */
    @Test
    void noNPlusOne() {
        var firstVisit = completedLater("Анна", slot(MONDAY, "08:00"));
        var firstKept = receivedByMaster("Мария", slot(MONDAY, "12:00"));
        var secondVisit = completedLater("Ольга", slot(MONDAY, "16:00"));
        var secondKept = receivedByMaster("Ирина", slot(MONDAY, "20:00"));
        var thirdVisit = completedLater("Елена", slot(TUESDAY, "08:00"));
        var lastSlot = slot(TUESDAY, "12:00");
        var thirdKept = receivedByMaster("Дарья", lastSlot);
        timeIsAfterStartOf(lastSlot, Duration.ofHours(1));
        marks.complete(firstVisit.id(), master);
        marks.markNoShow(firstKept.id(), master);

        var oneVisit = statements(() -> finance.visits(MONDAY, MONDAY, accountant));
        var oneKept = statements(() -> finance.keptPrepayments(TUESDAY, TUESDAY, accountant));

        marks.complete(secondVisit.id(), master);
        marks.complete(thirdVisit.id(), master);
        marks.markNoShow(secondKept.id(), master);
        marks.markNoShow(thirdKept.id(), master);
        assertThat(finance.visits(MONDAY, TUESDAY, accountant))
                .extracting(VisitFact::bookingId)
                .contains(firstVisit.id(), secondVisit.id(), thirdVisit.id());
        assertThat(finance.keptPrepayments(TUESDAY, TUESDAY, accountant))
                .extracting(KeptPrepaymentFact::bookingId)
                .contains(firstKept.id(), secondKept.id(), thirdKept.id());

        assertThat(oneVisit).isEqualTo(1);
        assertThat(statements(() -> finance.visits(MONDAY, TUESDAY, accountant)))
                .isEqualTo(oneVisit);
        assertThat(oneKept).isEqualTo(1);
        assertThat(statements(() -> finance.keptPrepayments(TUESDAY, TUESDAY, accountant)))
                .isEqualTo(oneKept);
    }

    private void rejected(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> finance.visits(from, to, accountant))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo(field));
        assertThatThrownBy(() -> finance.keptPrepayments(from, to, accountant))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo(field));
    }

    private VisitFact visitIn(LocalDate from, LocalDate to, BookingView booking) {
        return finance.visits(from, to, accountant).stream()
                .filter(fact -> fact.bookingId().equals(booking.id()))
                .findFirst()
                .orElse(null);
    }

    private KeptPrepaymentFact keptIn(LocalDate from, LocalDate to, BookingView booking) {
        return finance.keptPrepayments(from, to, accountant).stream()
                .filter(fact -> fact.bookingId().equals(booking.id()))
                .findFirst()
                .orElse(null);
    }

    private BookingView completed(String clientName, SlotView slot) {
        return completed(client(clientName), slot);
    }

    private BookingView completed(UUID clientId, SlotView slot) {
        var booking = bookings.createByMaster(manicure(clientId, slot), false, master);
        timeIsAfterStartOf(slot, Duration.ofHours(1));
        return marks.complete(booking.id(), master);
    }

    /** Запись мастером на слот; отмечается позже, когда тест переведёт часы. */
    private BookingView completedLater(String clientName, SlotView slot) {
        return bookings.createByMaster(manicure(client(clientName), slot), false, master);
    }

    /** Клиент с признаком предоплаты записался сам, предоплата получена (по умолчанию — 50 % от 2500). */
    private BookingView received(UUID clientId, SlotView slot) {
        return prepayments.markReceived(book(clientId, slot).id(), master);
    }

    /** Мастер записал клиента с ожиданием предоплаты, предоплата получена (50 % от 2500). */
    private BookingView receivedByMaster(String clientName, SlotView slot) {
        var booking = bookings.createByMaster(manicure(client(clientName), slot), true, master);
        return prepayments.markReceived(booking.id(), master);
    }

    private Instant salonAt(LocalDate day, int hour) {
        return salonTimeZone.at(day, LocalTime.of(hour, 0));
    }

    private long statements(Supplier<?> call) {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        entityManager.flush();
        entityManager.clear();
        var wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            call.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }
}
