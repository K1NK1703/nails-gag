package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingService;
import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateActualServices;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingSettingsChangeView;
import gag.nails.booking.model.BookingSettingsView;
import gag.nails.booking.model.BookingView;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.model.SlotView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Записи (спецификация API, §7.6): чтение, ведение, предоплата, предложение приехать раньше и отметки визита — по
 * разделу «Записи», свои записи — клиенту; правила записи — любому вошедшему, их изменение и журнал — по разделу
 * «Правила записи». Права и правила проверяет модуль; здесь — что адрес доходит до нужного вызова и отдаёт его ответ.
 *
 * <p>Слоты — на днях {@link #freshDay()}, в полдень: другие тесты их не трогают. Правила записи в базе одни на всех:
 * тесты, которые их меняют, возвращают прежние значения, а лимит активных записей читается из текущих правил. Тесты
 * со сдвигом часов входят после сдвига: сессия, открытая раньше, к сдвинутому моменту могла истечь.
 */
@ApiTest
class BookingsApiTest extends ApiTestSupport {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    BookingService bookings;

    @Autowired
    EarlierTimeOffers earlierTimeOffers;

    @Autowired
    BookingSettingsService bookingSettings;

    @Autowired
    CatalogService catalog;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ClientQueries clientQueries;

    @Test
    void clientBooksFreeSlot() {
        var customer = customerWithConsents();
        var clientId = actor(customer).requireClientId();
        var slot = freeSlot();
        var service = mainService();

        var booking = json(
                createByClient(customer, new CreateBooking(clientId, slot.id(), service.id(), List.of(), "Френч")),
                HttpStatus.CREATED);

        assertThat(field(booking, "clientId").asString()).isEqualTo(clientId.toString());
        assertThat(field(booking, "slotId").asString()).isEqualTo(slot.id().toString());
        assertThat(field(booking, "slotStartsAt").asString())
                .isEqualTo(slot.startsAt().toString());
        assertThat(field(booking, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(booking, "createdBy").asString()).isEqualTo("CLIENT");
        assertThat(field(booking, "comment").asString()).isEqualTo("Френч");
        assertThat(field(field(booking, "plannedServices").path(0), "serviceId").asString())
                .isEqualTo(service.id().toString());
    }

    @Test
    void clientCannotBookForSomeoneElse() {
        var customer = customerWithConsents();
        var foreign = createClient(uniqueLastName());

        expectProblem(
                createByClient(
                        customer,
                        new CreateBooking(
                                foreign.id(), freeSlot().id(), mainService().id(), List.of(), null)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void bookingBeyondLimitConflicts() {
        var customer = customerWithConsents();
        var clientId = actor(customer).requireClientId();
        var limit = bookingSettings.settings().maxActiveBookings();
        for (var i = 0; i < limit; i++) {
            bookFor(clientId, false);
        }

        var problem = json(
                createByClient(
                        customer,
                        new CreateBooking(
                                clientId, freeSlot().id(), mainService().id(), List.of(), null)),
                HttpStatus.CONFLICT);

        assertThat(field(problem, "code").asString()).isEqualTo("booking.limit_reached");
        assertThat(field(field(problem, "params"), "limit").asInt()).isEqualTo(limit);
    }

    @Test
    void masterBooksClientAwaitingPrepayment() {
        var target = createClient(uniqueLastName());
        var slot = freeSlot();
        var service = mainService();

        var booking = json(
                createByMaster(
                        signInMaster(),
                        Map.of(
                                "clientId", target.id(),
                                "slotId", slot.id(),
                                "mainServiceId", service.id(),
                                "addonServiceIds", List.of(),
                                "comment", "Записана по телефону",
                                "awaitPrepayment", true)),
                HttpStatus.CREATED);

        assertThat(field(booking, "clientId").asString()).isEqualTo(target.id().toString());
        assertThat(field(booking, "slotId").asString()).isEqualTo(slot.id().toString());
        assertThat(field(booking, "status").asString()).isEqualTo("AWAITING_PREPAYMENT");
        assertThat(field(booking, "createdBy").asString()).isEqualTo("MASTER");
        assertThat(field(booking, "prepaymentRequired").asBoolean()).isTrue();
        assertThat(field(booking, "comment").asString()).isEqualTo("Записана по телефону");
    }

    @Test
    void clientCannotBookAsMaster() {
        var customer = signInClient();

        expectProblem(
                createByMaster(
                        customer,
                        Map.of(
                                "clientId", actor(customer).requireClientId(),
                                "slotId", freeSlot().id(),
                                "mainServiceId", mainService().id(),
                                "addonServiceIds", List.of(),
                                "awaitPrepayment", false)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void clientReadsOwnBooking() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);

        var read = json(getBooking(customer, booking.id()));

        assertThat(field(read, "id").asString()).isEqualTo(booking.id().toString());
        assertThat(field(read, "slotStartsAt").asString())
                .isEqualTo(booking.slotStartsAt().toString());
        assertThat(field(read, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(read, "comment").isNull()).isTrue();
    }

    @Test
    void clientCannotReadForeignBooking() {
        var foreign = bookFor(createClient(uniqueLastName()).id(), false);

        expectProblem(getBooking(signInClient(), foreign.id()), HttpStatus.NOT_FOUND, "common.not_found");
    }

    @Test
    void staffGetsModuleCodeForMissingBooking() {
        var problem = json(getBooking(signInMaster(), UUID.randomUUID()), HttpStatus.NOT_FOUND);

        assertThat(field(problem, "code").asString()).isEqualTo("booking.booking_not_found");
    }

    @Test
    void bookingsRequireSession() {
        var day = freshDay();

        expectProblem(
                client.get().uri("/api/bookings?from={from}&to={to}", day, day),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    @Test
    void masterReadsBookingsOfPeriod() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var day = LocalDate.ofInstant(booking.slotStartsAt(), clock.getZone());

        var period = json(as(signInMaster(), client.get().uri("/api/bookings?from={from}&to={to}", day, day)));

        assertThat(ids(period)).containsExactly(booking.id());
    }

    @Test
    void clientCannotReadBookingsOfPeriod() {
        var day = freshDay();

        expectProblem(
                as(signInClient(), client.get().uri("/api/bookings?from={from}&to={to}", day, day)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void periodLongerThan93DaysIsRejected() {
        var from = freshDay();
        var to = from.plusDays(93);

        var problem = json(
                as(signInMaster(), client.get().uri("/api/bookings?from={from}&to={to}", from, to)),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("to");
    }

    /** Очередь общая для всей базы: проверяется, что в ней есть эта запись, а не её точный состав. */
    @Test
    void startedBookingAwaitsMark() {
        var started = bookFor(createClient(uniqueLastName()).id(), false);
        var later = bookFor(createClient(uniqueLastName()).id(), false);
        clock.set(started.slotStartsAt().plus(Duration.ofMinutes(5)));
        assertThat(later.slotStartsAt()).isAfter(clock.instant());

        var awaiting = json(as(signInMaster(), client.get().uri("/api/bookings/awaiting-mark")));

        assertThat(ids(awaiting)).contains(started.id()).doesNotContain(later.id());
    }

    /** Очередь общая для всей базы: проверяется, что в ней есть эта запись, а не её точный состав. */
    @Test
    void unpaidBookingAwaitsPrepayment() {
        var unpaid = bookFor(createClient(uniqueLastName()).id(), true);
        var confirmed = bookFor(createClient(uniqueLastName()).id(), false);

        var awaiting = json(as(signInMaster(), client.get().uri("/api/bookings/awaiting-prepayment")));

        assertThat(ids(awaiting)).contains(unpaid.id()).doesNotContain(confirmed.id());
    }

    @Test
    void clientCancelsOwnBookingFreeOfCharge() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);

        var cancelled = json(cancel(customer, booking.id()));

        assertThat(field(cancelled, "status").asString()).isEqualTo("CANCELLED");
        assertThat(field(cancelled, "cancelReason").asString()).isEqualTo("CLIENT");
        assertThat(field(cancelled, "cancelledAt").asString())
                .isEqualTo(clock.instant().toString());
    }

    @Test
    void masterRefusesLateClient() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(20)));

        var refused = json(refuseLateArrival(signInMaster(), booking.id()));

        assertThat(field(refused, "status").asString()).isEqualTo("CANCELLED");
        assertThat(field(refused, "cancelReason").asString()).isEqualTo("LATE_ARRIVAL");
    }

    @Test
    void refusingBeforeVisitStartConflicts() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);

        expectProblem(
                refuseLateArrival(signInMaster(), booking.id()), HttpStatus.CONFLICT, "booking.visit_not_started");
    }

    @Test
    void clientReschedulesOwnBooking() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        var newSlot = freeSlot();

        var moved = json(reschedule(customer, booking.id(), newSlot.id()));

        assertThat(field(moved, "id").asString()).isEqualTo(booking.id().toString());
        assertThat(field(moved, "slotId").asString()).isEqualTo(newSlot.id().toString());
        assertThat(field(moved, "slotStartsAt").asString())
                .isEqualTo(newSlot.startsAt().toString());
    }

    @Test
    void clientSetsAndClearsComment() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);

        var commented = json(setComment(customer, booking.id(), "Опоздаю на пять минут"));
        assertThat(field(commented, "comment").asString()).isEqualTo("Опоздаю на пять минут");

        var cleared = json(setComment(customer, booking.id(), null));
        assertThat(field(cleared, "comment").isNull()).isTrue();
    }

    @Test
    void masterReplacesAndReadsNote() {
        var master = signInMaster();
        var booking = bookFor(createClient(uniqueLastName()).id(), false);

        assertThat(field(json(readNote(master, booking.id())), "note").isNull()).isTrue();

        setNote(master, booking.id(), "Просила тёмный лак")
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(field(json(readNote(master, booking.id())), "note").asString())
                .isEqualTo("Просила тёмный лак");
    }

    @Test
    void clientCannotReadNoteOfOwnBooking() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);

        expectProblem(readNote(customer, booking.id()), HttpStatus.NOT_FOUND, "common.not_found");
    }

    @Test
    void masterMarksPrepaymentReceived() {
        var booking = bookFor(createClient(uniqueLastName()).id(), true);

        var paid = json(markPrepaymentReceived(signInMaster(), booking.id()));

        assertThat(field(paid, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(paid, "prepaymentReceivedAt").asString())
                .isEqualTo(clock.instant().toString());
        assertThat(field(paid, "prepaymentDeadline").isNull()).isTrue();
    }

    @Test
    void masterConfirmsWithoutPrepaymentAndClearsClientFlag() {
        var target = createClient(uniqueLastName());
        clientService.setPrepaymentRequired(target.id(), true, null, developerActor());
        var booking = bookFor(target.id(), true);

        var confirmed = json(confirmWithoutPrepayment(signInMaster(), booking.id()));

        assertThat(field(confirmed, "status").asString()).isEqualTo("CONFIRMED");
        assertThat(field(confirmed, "prepaymentReceivedAt").isNull()).isTrue();
        assertThat(clientQueries.byId(target.id()).orElseThrow().prepaymentRequired())
                .isFalse();
    }

    @Test
    void masterProposesAndWithdrawsEarlierTime() {
        var master = signInMaster();
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var proposedStart = booking.slotStartsAt().minus(Duration.ofHours(1));

        var proposed = json(proposeEarlierTime(master, booking.id(), proposedStart));
        var offer = field(proposed, "earlierTime");
        assertThat(field(offer, "proposedStart").asString()).isEqualTo(proposedStart.toString());
        assertThat(field(offer, "proposedAt").asString())
                .isEqualTo(clock.instant().toString());
        assertThat(field(offer, "status").asString()).isEqualTo("PENDING");

        var withdrawn = json(withdrawEarlierTime(master, booking.id()));
        assertThat(field(field(withdrawn, "earlierTime"), "status").asString()).isEqualTo("WITHDRAWN");
        assertThat(field(withdrawn, "agreedStart").isNull()).isTrue();
    }

    @Test
    void clientAcceptsAndDeclinesEarlierTime() {
        var customer = signInClient();
        var booking = bookFor(actor(customer).requireClientId(), false);
        var oneHourEarlier = booking.slotStartsAt().minus(Duration.ofHours(1));
        earlierTimeOffers.propose(booking.id(), oneHourEarlier, developerActor());

        var accepted = json(acceptEarlierTime(customer, booking.id()));
        assertThat(field(field(accepted, "earlierTime"), "status").asString()).isEqualTo("ACCEPTED");
        assertThat(field(accepted, "agreedStart").asString()).isEqualTo(oneHourEarlier.toString());

        earlierTimeOffers.propose(booking.id(), booking.slotStartsAt().minus(Duration.ofHours(2)), developerActor());

        var declined = json(declineEarlierTime(customer, booking.id()));
        assertThat(field(field(declined, "earlierTime"), "status").asString()).isEqualTo("DECLINED");
        assertThat(field(declined, "agreedStart").asString()).isEqualTo(oneHourEarlier.toString());
    }

    @Test
    void masterCompletesVisitAndCorrectsActualServices() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var extra = mainService();
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        var master = signInMaster();

        var completed = json(complete(master, booking.id()));
        assertThat(field(completed, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(completed, "completedAt").asString())
                .isEqualTo(clock.instant().toString());
        assertThat(field(completed, "finalAmount").decimalValue()).isEqualByComparingTo("1500.00");

        var corrected = json(updateActualServices(
                master, booking.id(), new UpdateActualServices(extra.id(), List.of(), new BigDecimal("1200.00"))));
        assertThat(field(field(corrected, "actualServices").path(0), "serviceId")
                        .asString())
                .isEqualTo(extra.id().toString());
        assertThat(field(corrected, "actualServices").size()).isEqualTo(1);
        assertThat(field(corrected, "finalAmount").decimalValue()).isEqualByComparingTo("1200.00");
    }

    @Test
    void masterCorrectsCompletionToNoShow() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        var master = signInMaster();
        json(complete(master, booking.id()));

        var noShow = json(markNoShow(master, booking.id()));

        assertThat(field(noShow, "status").asString()).isEqualTo("NO_SHOW");
        assertThat(field(noShow, "noShowAt").asString())
                .isEqualTo(clock.instant().toString());
        assertThat(field(noShow, "actualServices").size()).isZero();
        assertThat(field(noShow, "finalAmount").isNull()).isTrue();
    }

    @Test
    void settingsReadableBySession() {
        var current = bookingSettings.settings();

        expectProblem(client.get().uri("/api/booking-settings"), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        var read = json(as(signInClient(), client.get().uri("/api/booking-settings")));

        assertThat(field(read, "maxActiveBookings").asInt()).isEqualTo(current.maxActiveBookings());
        assertThat(field(read, "bookingCutoffMinutes").asInt()).isEqualTo(current.bookingCutoffMinutes());
        assertThat(field(read, "prepaymentHoldHours").asInt()).isEqualTo(current.prepaymentHoldHours());
        assertThat(field(read, "freeCancelWindowHours").asInt()).isEqualTo(current.freeCancelWindowHours());
        assertThat(field(read, "prepaymentPercent").asInt()).isEqualTo(current.prepaymentPercent());
        assertThat(field(read, "lateArrivalToleranceMinutes").asInt()).isEqualTo(current.lateArrivalToleranceMinutes());
        assertThat(field(read, "markCorrectionWindowDays").asInt()).isEqualTo(current.markCorrectionWindowDays());
    }

    @Test
    void masterUpdatesSettings() {
        var current = bookingSettings.settings();
        var tolerance = otherTolerance(current.lateArrivalToleranceMinutes());
        try {
            var updated = json(updateSettings(signInMaster(), withTolerance(current, tolerance)));

            assertThat(field(updated, "lateArrivalToleranceMinutes").asInt()).isEqualTo(tolerance);
            assertThat(field(updated, "maxActiveBookings").asInt()).isEqualTo(current.maxActiveBookings());
            assertThat(bookingSettings.settings().lateArrivalToleranceMinutes()).isEqualTo(tolerance);
        } finally {
            bookingSettings.update(withTolerance(current, current.lateArrivalToleranceMinutes()), developerActor());
        }
    }

    @Test
    void clientCannotUpdateSettings() {
        var current = bookingSettings.settings();
        var tolerance = otherTolerance(current.lateArrivalToleranceMinutes());
        try {
            expectProblem(
                    updateSettings(signInClient(), withTolerance(current, tolerance)),
                    HttpStatus.NOT_FOUND,
                    "common.not_found");
            assertThat(bookingSettings.settings()).isEqualTo(current);
        } finally {
            bookingSettings.update(withTolerance(current, current.lateArrivalToleranceMinutes()), developerActor());
        }
    }

    /**
     * Журнал общий для всей базы, а часы в тестах стоят: такая же строка могла остаться от прошлых тестов и прогонов,
     * поэтому проверяется, что совпадающих строк стало на одну больше.
     */
    @Test
    void settingsChangeAppearsInHistory() {
        var current = bookingSettings.settings();
        var oldValue = current.lateArrivalToleranceMinutes();
        var newValue = otherTolerance(oldValue);
        var before = bookingSettings.history(developerActor()).stream()
                .filter(change -> isToleranceChange(change, oldValue, newValue))
                .count();
        try {
            bookingSettings.update(withTolerance(current, newValue), developerActor());

            var history = json(as(signInMaster(), client.get().uri("/api/booking-settings/history")));

            var matching = history.values().stream()
                    .filter(entry -> field(entry, "field").asString().equals("late_arrival_tolerance_minutes")
                            && field(entry, "oldValue").asString().equals(String.valueOf(oldValue))
                            && field(entry, "newValue").asString().equals(String.valueOf(newValue))
                            && field(entry, "changedAt")
                                    .asString()
                                    .equals(clock.instant().toString()))
                    .count();
            assertThat(matching).isEqualTo(before + 1);
        } finally {
            bookingSettings.update(withTolerance(current, oldValue), developerActor());
        }
    }

    private RequestHeadersSpec<?> createByClient(Session session, CreateBooking command) {
        return as(session, csrf(client.post().uri("/api/bookings")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    private RequestHeadersSpec<?> createByMaster(Session session, Map<String, Object> body) {
        return as(session, csrf(client.post().uri("/api/bookings/by-master")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> getBooking(Session session, UUID bookingId) {
        return as(session, client.get().uri("/api/bookings/{id}", bookingId));
    }

    private RequestHeadersSpec<?> cancel(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "cancel");
    }

    private RequestHeadersSpec<?> refuseLateArrival(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "refuse-late-arrival");
    }

    private RequestHeadersSpec<?> reschedule(Session session, UUID bookingId, UUID slotId) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/reschedule", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("slotId", slotId));
    }

    private RequestHeadersSpec<?> setComment(Session session, UUID bookingId, @Nullable String comment) {
        // Map.of не принимает null, а стирание комментария — явный null в теле.
        var body = new HashMap<String, @Nullable Object>();
        body.put("comment", comment);
        return as(session, csrf(client.put().uri("/api/bookings/{id}/comment", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> readNote(Session session, UUID bookingId) {
        return as(session, client.get().uri("/api/bookings/{id}/note", bookingId));
    }

    private RequestHeadersSpec<?> setNote(Session session, UUID bookingId, String note) {
        return as(session, csrf(client.put().uri("/api/bookings/{id}/note", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("note", note));
    }

    private RequestHeadersSpec<?> markPrepaymentReceived(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "prepayment/received");
    }

    private RequestHeadersSpec<?> confirmWithoutPrepayment(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "prepayment/confirm-without");
    }

    private RequestHeadersSpec<?> proposeEarlierTime(Session session, UUID bookingId, Instant proposedStart) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/earlier-time", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("proposedStart", proposedStart));
    }

    private RequestHeadersSpec<?> withdrawEarlierTime(Session session, UUID bookingId) {
        return as(session, csrf(client.delete().uri("/api/bookings/{id}/earlier-time", bookingId)));
    }

    private RequestHeadersSpec<?> acceptEarlierTime(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "earlier-time/accept");
    }

    private RequestHeadersSpec<?> declineEarlierTime(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "earlier-time/decline");
    }

    private RequestHeadersSpec<?> complete(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "complete");
    }

    private RequestHeadersSpec<?> markNoShow(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "no-show");
    }

    private RequestHeadersSpec<?> updateActualServices(Session session, UUID bookingId, UpdateActualServices command) {
        return as(session, csrf(client.put().uri("/api/bookings/{id}/actual-services", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    private RequestHeadersSpec<?> updateSettings(Session session, UpdateBookingSettings command) {
        return as(session, csrf(client.put().uri("/api/booking-settings")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    /** Переход записи без тела: {@code POST /api/bookings/{id}/<действие>}. */
    private RequestHeadersSpec<?> bookingAction(Session session, UUID bookingId, String action) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/" + action, bookingId)));
    }

    /** Ответ — ошибка с данным статусом и кодом. */
    private static void expectProblem(RequestHeadersSpec<?> spec, HttpStatus status, String code) {
        assertThat(field(json(spec, status), "code").asString()).isEqualTo(code);
    }

    /** Поле объекта; его отсутствие — провал теста, а не пустое значение. */
    private static JsonNode field(JsonNode node, String name) {
        assertThat(node.has(name)).as("field " + name + " in " + node).isTrue();
        return node.get(name);
    }

    /** Новый клиент с согласиями на действующие политику и оферту: без них записаться сам он не может. */
    private Session customerWithConsents() {
        var customer = signInClient();
        var offer = documents.current(DocumentKind.OFFER).version();
        clientService.recordConsent(
                actor(customer).requireClientId(),
                new RecordConsent(ConsentKind.OFFER, offer, ConsentSource.SITE_CHECKBOX),
                actor(customer));
        return customer;
    }

    /** Подтверждённая или ждущая предоплату запись клиента на свой день в полдень; её заводит разработчик. */
    private BookingView bookFor(UUID clientId, boolean awaitPrepayment) {
        return bookings.createByMaster(
                new CreateBooking(clientId, freeSlot().id(), mainService().id(), List.of(), null),
                awaitPrepayment,
                developerActor());
    }

    /** Свободный слот в полдень дня, которого нет у других тестов. */
    private SlotView freeSlot() {
        return schedule.addSlot(freshDay(), NOON, developerActor());
    }

    /** Основная услуга за 1500,00; её заводит разработчик. */
    private ServiceView mainService() {
        return catalog.create(
                new CreateService(
                        ServiceKind.MAIN,
                        "Услуга " + UUID.randomUUID(),
                        "Описание",
                        new BigDecimal("1500.00"),
                        60,
                        "Маникюр",
                        false,
                        false),
                developerActor());
    }

    /** Те же правила с другим допустимым опозданием. */
    private static UpdateBookingSettings withTolerance(BookingSettingsView settings, int toleranceMinutes) {
        return new UpdateBookingSettings(
                settings.maxActiveBookings(),
                settings.bookingCutoffMinutes(),
                settings.prepaymentHoldHours(),
                settings.freeCancelWindowHours(),
                settings.prepaymentPercent(),
                toleranceMinutes,
                settings.markCorrectionWindowDays());
    }

    /** Другое допустимое опоздание (0–120 минут): на 5 минут больше текущего, у наибольшего — меньше. */
    private static int otherTolerance(int current) {
        return current < 120 ? current + 5 : current - 5;
    }

    private static boolean isToleranceChange(BookingSettingsChangeView change, int oldValue, int newValue) {
        return change.field().equals("late_arrival_tolerance_minutes")
                && change.oldValue().equals(String.valueOf(oldValue))
                && change.newValue().equals(String.valueOf(newValue));
    }
}
