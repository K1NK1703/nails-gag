package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.identity.testing.FakeProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Деньги записи (спецификация финансов, §3, §5): итог визита с причиной суммы, пакетная отметка «выполнены по прайсу»,
 * сумма и исход предоплаты, записи на рассмотрении. Правила проверяет модуль, здесь — что адреса доходят до нужного
 * вызова и что клиенту денежные поля его записи не уходят.
 */
@ApiTest
class BookingFinanceApiTest extends BookingApiTestSupport {

    /** Денежные поля записи: клиенту — всегда {@code null}. */
    private static final List<String> MONEY =
            List.of("finalAmount", "amountNote", "prepaymentAmount", "prepaymentOutcome");

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @Test
    void masterSetsVisitWithDiscountAndReason() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var serviceId = booking.plannedServices().getFirst().serviceId();
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        marks.complete(booking.id(), developerActor());
        var master = signInMaster();

        var visit = json(updateVisit(master, booking.id(), visitBody(serviceId, "1000", "Постоянная клиентка")));

        assertThat(field(visit, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(visit, "finalAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(field(visit, "amountNote").asString()).isEqualTo("Постоянная клиентка");
        assertThat(field(field(visit, "actualServices").path(0), "serviceId").asString())
                .isEqualTo(serviceId.toString());

        var free = json(updateVisit(master, booking.id(), visitBody(serviceId, "0", null)));
        assertThat(field(free, "finalAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(field(free, "amountNote").isNull()).isTrue();
    }

    @Test
    void invalidVisitIsRejected() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var serviceId = booking.plannedServices().getFirst().serviceId();
        var master = signInMaster();

        expectProblem(
                updateVisit(master, booking.id(), visitBody(serviceId, "1000", null)),
                HttpStatus.CONFLICT,
                "booking.invalid_transition");
        expectInvalid(updateVisit(master, booking.id(), visitBody(serviceId, "-1", null)), "finalAmount");
        expectInvalid(updateVisit(master, booking.id(), visitBody(serviceId, "1000", "п".repeat(201))), "amountNote");
    }

    @Test
    void batchMarksStartedVisitsAndReportsTheRest() {
        var started = bookFor(createClient(uniqueLastName()).id(), false);
        var later = bookFor(createClient(uniqueLastName()).id(), false);
        assertThat(later.slotStartsAt()).isAfter(started.slotStartsAt());
        var unknown = UUID.randomUUID();
        clock.set(started.slotStartsAt().plus(Duration.ofMinutes(90)));
        var master = signInMaster();

        var results = json(completeAsPlanned(master, List.of(started.id(), later.id(), unknown, started.id())));

        assertThat(results.values())
                .extracting(result -> field(result, "bookingId").asString())
                .containsExactly(started.id().toString(), later.id().toString(), unknown.toString());
        assertThat(field(results.path(0), "errorCode").isNull()).isTrue();
        assertThat(field(results.path(1), "errorCode").asString()).isEqualTo("booking.visit_not_started");
        assertThat(field(results.path(2), "errorCode").asString()).isEqualTo("booking.booking_not_found");
        var completed = json(as(master, client.get().uri("/api/bookings/{id}", started.id())));
        assertThat(field(completed, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(completed, "finalAmount").decimalValue()).isEqualByComparingTo("1500.00");
    }

    @Test
    void batchOverFiftyIsRejected() {
        var master = signInMaster();
        var ids = IntStream.range(0, VisitMarks.MAX_BATCH + 1)
                .mapToObj(index -> UUID.randomUUID())
                .toList();

        expectInvalid(completeAsPlanned(master, ids), "bookingIds");
        expectInvalid(
                as(master, csrf(client.post().uri("/api/bookings/complete-as-planned")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of()),
                "bookingIds");
    }

    @Test
    void clientCannotMarkInBatch() {
        expectProblem(
                completeAsPlanned(signInClient(), List.of(UUID.randomUUID())),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    /** Без тела — сумма по умолчанию: процент предоплаты из правил от прайса, до рубля, не меньше рубля. */
    @Test
    void prepaymentWithoutBodyTakesDefaultAmount() {
        var booking = bookFor(createClient(uniqueLastName()).id(), true);
        var expected = defaultPrepayment();

        var paid = json(bookingAction(signInMaster(), booking.id(), "prepayment/received"));

        assertThat(field(paid, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(paid, "prepaymentAmount").decimalValue()).isEqualByComparingTo(expected);
        assertThat(field(paid, "prepaymentOutcome").asString()).isEqualTo("APPLIED");
    }

    @Test
    void prepaymentWithBodyTakesGivenAmount() {
        var master = signInMaster();
        var withAmount = bookFor(createClient(uniqueLastName()).id(), true);
        var withNull = bookFor(createClient(uniqueLastName()).id(), true);

        expectInvalid(receivePrepayment(master, withAmount.id(), amountBody(new BigDecimal("0"))), "amount");
        var paid = json(receivePrepayment(master, withAmount.id(), amountBody(new BigDecimal("700"))));
        assertThat(field(paid, "prepaymentAmount").decimalValue()).isEqualByComparingTo("700.00");

        var byDefault = json(receivePrepayment(master, withNull.id(), amountBody(null)));
        assertThat(field(byDefault, "prepaymentAmount").decimalValue()).isEqualByComparingTo(defaultPrepayment());
    }

    /** Пустое тело с {@code Content-Type: application/json} — как без тела: сумма по умолчанию. */
    @Test
    void emptyJsonBodyTakesDefaultAmount() {
        var booking = bookFor(createClient(uniqueLastName()).id(), true);

        var paid =
                json(as(signInMaster(), csrf(client.post().uri("/api/bookings/{id}/prepayment/received", booking.id())))
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(field(paid, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(paid, "prepaymentAmount").decimalValue()).isEqualByComparingTo(defaultPrepayment());
    }

    @Test
    void masterOverridesPrepaymentOutcome() {
        var booking = bookFor(createClient(uniqueLastName()).id(), true);
        prepayments.markReceived(booking.id(), new BigDecimal("500"), developerActor());
        bookings.cancel(booking.id(), developerActor());
        var master = signInMaster();
        assertThat(field(json(as(master, client.get().uri("/api/bookings/{id}", booking.id()))), "prepaymentOutcome")
                        .asString())
                .isEqualTo("RETURNED");

        var kept = json(chooseOutcome(master, booking.id(), "KEPT"));

        assertThat(field(kept, "prepaymentOutcome").asString()).isEqualTo("KEPT");
        assertThat(field(kept, "prepaymentAmount").decimalValue()).isEqualByComparingTo("500.00");
        expectInvalid(chooseOutcome(master, booking.id(), "APPLIED"), "outcome");
        var unpaid = bookFor(createClient(uniqueLastName()).id(), false);
        expectProblem(chooseOutcome(master, unpaid.id(), "KEPT"), HttpStatus.CONFLICT, "booking.invalid_transition");
    }

    @Test
    void underReviewListsBookingsOfErasedClients() {
        var customer = signInClient();
        var clientId = actor(customer).requireClientId();
        var booking = bookFor(clientId, false);
        var regular = bookFor(createClient(uniqueLastName()).id(), false);
        clientService.erase(clientId);
        var master = signInMaster();

        var underReview = as(master, client.get().uri("/api/bookings/under-review"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();

        assertThat(ids(Objects.requireNonNull(underReview)))
                .contains(booking.id())
                .doesNotContain(regular.id());
        var listed = underReview.values().stream()
                .filter(item -> field(item, "id").asString().equals(booking.id().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(field(listed, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(listed, "clientErasedAt").asString())
                .isEqualTo(clock.instant().toString());
        expectProblem(
                as(signInClient(), client.get().uri("/api/bookings/under-review")),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    /** Клиент на сайте видит только прайс: денежные поля своей записи приходят ему пустыми, мастеру — заполненными. */
    @Test
    void clientSeesNoMoneyInOwnBooking() {
        var profile = new FakeProfile(uniqueSubject(), uniquePhone(), null, "Клиентка", null);
        var clientId = actor(signIn(profile)).requireClientId();
        var booking = bookFor(clientId, true);
        var serviceId = booking.plannedServices().getFirst().serviceId();
        prepayments.markReceived(booking.id(), new BigDecimal("500"), developerActor());
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        marks.complete(booking.id(), developerActor());
        marks.updateVisit(
                booking.id(),
                new UpdateVisit(serviceId, List.of(), new BigDecimal("1300"), "Скидка"),
                developerActor());
        // Сессия, открытая до сдвига часов, к этому моменту истекла: клиентка входит снова.
        var customer = signIn(profile);

        var own = json(as(customer, client.get().uri("/api/bookings/{id}", booking.id())));
        var history = json(as(customer, client.get().uri("/api/clients/{id}/bookings?scope=history", clientId)));
        var forMaster = json(as(signInMaster(), client.get().uri("/api/bookings/{id}", booking.id())));

        assertThat(field(own, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(own, "prepaymentReceivedAt").isNull()).isFalse();
        var inHistory = history.values().stream()
                .filter(item -> field(item, "id").asString().equals(booking.id().toString()))
                .findFirst()
                .orElseThrow();
        for (var money : MONEY) {
            assertThat(field(own, money).isNull()).as(money).isTrue();
            assertThat(field(inHistory, money).isNull()).as(money).isTrue();
            assertThat(field(forMaster, money).isNull()).as(money).isFalse();
        }
    }

    private RequestHeadersSpec<?> updateVisit(Session session, UUID bookingId, Map<String, @Nullable Object> body) {
        return as(session, csrf(client.put().uri("/api/bookings/{id}/visit", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> completeAsPlanned(Session session, List<UUID> bookingIds) {
        return as(session, csrf(client.post().uri("/api/bookings/complete-as-planned")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("bookingIds", new ArrayList<>(bookingIds)));
    }

    private RequestHeadersSpec<?> receivePrepayment(
            Session session, UUID bookingId, Map<String, @Nullable Object> body) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/prepayment/received", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> chooseOutcome(Session session, UUID bookingId, String outcome) {
        return as(session, csrf(client.put().uri("/api/bookings/{id}/prepayment/outcome", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("outcome", outcome));
    }

    /**
     * Сумма предоплаты по умолчанию для записи на услугу {@link #mainService()} за 1500: процент из текущих правил, до
     * рубля, половина — вверх, не меньше рубля.
     */
    private BigDecimal defaultPrepayment() {
        return new BigDecimal("1500")
                .multiply(BigDecimal.valueOf(bookingSettings.settings().prepaymentPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE);
    }

    private static Map<String, @Nullable Object> visitBody(
            UUID mainServiceId, String finalAmount, @Nullable String amountNote) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("mainServiceId", mainServiceId);
        body.put("addonServiceIds", List.of());
        body.put("finalAmount", new BigDecimal(finalAmount));
        body.put("amountNote", amountNote);
        return body;
    }

    private static Map<String, @Nullable Object> amountBody(@Nullable BigDecimal amount) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("amount", amount);
        return body;
    }

    /** Ошибка проверки: {@code 400 common.validation} и имя поля первого нарушения. */
    private static void expectInvalid(RequestHeadersSpec<?> spec, String field) {
        var problem = json(spec, HttpStatus.BAD_REQUEST);
        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo(field);
    }
}
