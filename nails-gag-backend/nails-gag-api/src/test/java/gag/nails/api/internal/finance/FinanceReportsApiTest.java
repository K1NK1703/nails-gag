package gag.nails.api.internal.finance;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingService;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.finance.model.EntryKind;
import gag.nails.schedule.ScheduleService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Отчёты финансов (спецификация финансов, §5): сводка, помесячно, по услугам, журнал операций. Суммы считает модуль,
 * здесь — что адреса отдают его ответ целиком, журнал постраничен, виды операций в адресе — через дефис. Данные теста —
 * на дне {@link #freshDay()}, где других записей на визит и ручных записей нет: визит со скидкой, удержанная
 * предоплата, ручные доход и расход.
 */
@ApiTest
class FinanceReportsApiTest extends FinanceApiTestSupport {

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    VisitMarks marks;

    @Test
    void summaryCombinesVisitsPrepaymentsAndEntries() {
        var day = dayWithOperations();

        var summary = noStore(get(day.master(), "/api/finance/summary?from={day}&to={day}", day.day(), day.day()));

        assertThat(field(summary, "from").asString()).isEqualTo(day.day().toString());
        var visits = field(summary, "visits");
        assertThat(field(visits, "count").asInt()).isEqualTo(1);
        assertThat(field(visits, "priceSum").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(field(visits, "discounts").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(field(visits, "surcharges").decimalValue()).isEqualByComparingTo("0");
        assertThat(field(visits, "received").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(field(visits, "averageCheck").decimalValue()).isEqualByComparingTo("1200.00");
        var kept = field(summary, "keptPrepayments");
        assertThat(field(kept, "count").asInt()).isEqualTo(1);
        assertThat(field(kept, "amount").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(field(summary, "incomeByCategory").values()).singleElement().satisfies(total -> {
            assertThat(field(total, "categoryId").asString())
                    .isEqualTo(day.incomeCategoryId().toString());
            assertThat(field(total, "hidden").asBoolean()).isFalse();
            assertThat(field(total, "amount").decimalValue()).isEqualByComparingTo("300.00");
        });
        assertThat(field(summary, "expenseByCategory").size()).isEqualTo(1);
        assertThat(field(summary, "income").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(field(summary, "expenses").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(field(summary, "profit").decimalValue()).isEqualByComparingTo("1900.00");
    }

    @Test
    void monthlyHasTwelveMonths() {
        var master = signInMaster();

        var months = noStore(get(master, "/api/finance/monthly?year=2026"));

        assertThat(months.values())
                .extracting(month -> field(month, "month").asString())
                .containsExactly(
                        "2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08",
                        "2026-09", "2026-10", "2026-11", "2026-12");
        assertThat(months.values()).allSatisfy(month -> {
            assertThat(field(month, "income").isNumber()).isTrue();
            assertThat(field(month, "expenses").isNumber()).isTrue();
            assertThat(field(month, "profit").isNumber()).isTrue();
        });
        expectInvalid(get(master, "/api/finance/monthly?year=1999"), "year");
        expectInvalid(get(master, "/api/finance/monthly"), "year");
    }

    @Test
    void servicesReportCountsActualServices() {
        var day = dayWithOperations();

        var report = noStore(get(day.master(), "/api/finance/services?from={day}&to={day}", day.day(), day.day()));

        assertThat(field(report, "visits").asInt()).isEqualTo(1);
        assertThat(field(report, "averageCheck").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(field(report, "services").values()).singleElement().satisfies(service -> {
            assertThat(field(service, "serviceId").asString())
                    .isEqualTo(day.serviceId().toString());
            assertThat(field(service, "kind").asString()).isEqualTo("MAIN");
            assertThat(field(service, "count").asInt()).isEqualTo(1);
            assertThat(field(service, "priceSum").decimalValue()).isEqualByComparingTo("1500.00");
        });
    }

    @Test
    void journalIsPagedAndFilteredByKebabTypes() {
        var day = dayWithOperations();
        var master = day.master();

        var first = noStore(get(master, "/api/finance/journal?from={day}&to={day}&size=2", day.day(), day.day()));
        var second = json(get(master, "/api/finance/journal?from={day}&to={day}&page=1&size=2", day.day(), day.day()));

        assertThat(field(first, "total").asLong()).isEqualTo(4);
        assertThat(field(first, "size").asInt()).isEqualTo(2);
        assertThat(types(field(first, "items"))).containsExactly("VISIT", "KEPT_PREPAYMENT");
        assertThat(field(field(first, "items").path(0), "bookingId").asString())
                .isEqualTo(day.visitId().toString());
        assertThat(field(second, "page").asInt()).isEqualTo(1);
        assertThat(types(field(second, "items"))).containsExactlyInAnyOrder("INCOME", "EXPENSE");

        var filtered = json(get(
                master,
                "/api/finance/journal?from={day}&to={day}&type=kept-prepayment&type=income",
                day.day(),
                day.day()));
        assertThat(types(field(filtered, "items"))).containsExactly("KEPT_PREPAYMENT", "INCOME");
        var byCategory = json(get(
                master,
                "/api/finance/journal?from={day}&to={day}&categoryId={id}",
                day.day(),
                day.day(),
                day.incomeCategoryId()));
        assertThat(types(field(byCategory, "items"))).containsExactly("INCOME");
        assertThat(field(byCategory, "size").asInt()).isEqualTo(50);
    }

    /** Вид операции в адресе — только через дефис: имя константы Java — неверное значение параметра. */
    @Test
    void journalTypeInJavaCaseIsBadRequest() {
        var master = signInMaster();

        for (var type : List.of("VISIT", "KEPT_PREPAYMENT", "kept_prepayment", "visits")) {
            expectInvalid(get(master, "/api/finance/journal?from=2026-03-01&to=2026-03-01&type={type}", type), "type");
        }
        expectInvalid(get(master, "/api/finance/journal?from=2026-03-01&to=2026-03-01&type=visit&type=INCOME"), "type");
    }

    @Test
    void periodIsChecked() {
        var master = signInMaster();

        expectInvalid(get(master, "/api/finance/summary?from=2025-01-01&to=2026-01-02"), "to");
        expectInvalid(get(master, "/api/finance/services?from=2026-03-02&to=2026-03-01"), "to");
        expectInvalid(get(master, "/api/finance/journal?from=1999-12-31&to=2000-01-01"), "from");
        expectInvalid(get(master, "/api/finance/summary?from=2026-03-01"), "to");
    }

    @Test
    void developerReads() {
        var developer = signInDeveloper();

        noStore(get(developer, "/api/finance/summary?from=2026-03-01&to=2026-03-31"));
        noStore(get(developer, "/api/finance/journal?from=2026-03-01&to=2026-03-31"));
    }

    @Test
    void clientGetsNotFound() {
        var customer = signInClient();

        for (var uri : List.of(
                "/api/finance/summary?from=2026-03-01&to=2026-03-31",
                "/api/finance/monthly?year=2026",
                "/api/finance/services?from=2026-03-01&to=2026-03-31",
                "/api/finance/journal?from=2026-03-01&to=2026-03-31")) {
            expectProblem(get(customer, uri), HttpStatus.NOT_FOUND, "common.not_found");
        }
    }

    @Test
    void anonymousGetsUnauthenticated() {
        expectProblem(
                client.get().uri("/api/finance/summary?from=2026-03-01&to=2026-03-31"),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    /**
     * Операции одного дня {@link #freshDay()}: визит по услуге за 1500 с итогом 1200, удержанная предоплата 500
     * (неявка), ручной доход 300 и расход 100. Часы переходят на этот день; мастер входит после сдвига.
     */
    private Operations dayWithOperations() {
        var developer = developerActor();
        var day = freshDay();
        var clientId = createClient(uniqueLastName()).id();
        var service = mainService();
        var visitSlot = schedule.addSlot(day, LocalTime.NOON, developer);
        var prepaidSlot = schedule.addSlot(day, LocalTime.of(15, 0), developer);
        var visit = bookings.createByMaster(
                new CreateBooking(clientId, visitSlot.id(), service.id(), List.of(), null), false, developer);
        var prepaid = bookings.createByMaster(
                new CreateBooking(clientId, prepaidSlot.id(), service.id(), List.of(), null), true, developer);
        prepayments.markReceived(prepaid.id(), new BigDecimal("500"), developer);

        clock.set(prepaidSlot.startsAt().plus(Duration.ofMinutes(90)));
        marks.complete(visit.id(), developer);
        marks.updateVisit(
                visit.id(), new UpdateVisit(service.id(), List.of(), new BigDecimal("1200"), "Скидка"), developer);
        marks.markNoShow(prepaid.id(), developer);
        var master = signInMaster();
        var income = category(actor(master), EntryKind.INCOME);
        var expense = category(actor(master), EntryKind.EXPENSE);
        entry(actor(master), income.id(), "300", day);
        entry(actor(master), expense.id(), "100", day);
        return new Operations(master, day, service.id(), visit.id(), income.id());
    }

    private static List<String> types(JsonNode items) {
        return items.values().stream()
                .map(item -> field(item, "type").asString())
                .toList();
    }

    private record Operations(Session master, LocalDate day, UUID serviceId, UUID visitId, UUID incomeCategoryId) {}
}
