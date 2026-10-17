package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.booking.command.CreateBooking;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.command.CreateRole;
import gag.nails.identity.testing.FakeProfile;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/**
 * Записи как ресурс (спецификация API, §7.6): создание, чтение и очереди, отмена, отказ опоздавшему, перенос,
 * комментарий и заметка мастера. Сотруднику — по разделу «Записи», свои записи — клиенту; права и правила проверяет
 * модуль, здесь — что адрес доходит до нужного вызова и отдаёт его ответ. Лимит активных записей и окно бесплатной
 * отмены читаются из текущих правил: они в базе одни на всех.
 */
@ApiTest
class BookingsApiTest extends BookingApiTestSupport {

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
        var planned = field(booking, "plannedServices");
        assertThat(planned.size()).isEqualTo(1);
        assertThat(field(planned.path(0), "serviceId").asString())
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
        var body = byMasterBody(target.id(), slot.id(), mainService().id(), true);
        body.put("comment", "Записана по телефону");

        var booking = json(createByMaster(signInMaster(), body), HttpStatus.CREATED);

        assertThat(field(booking, "clientId").asString()).isEqualTo(target.id().toString());
        assertThat(field(booking, "slotId").asString()).isEqualTo(slot.id().toString());
        assertThat(field(booking, "status").asString()).isEqualTo("AWAITING_PREPAYMENT");
        assertThat(field(booking, "createdBy").asString()).isEqualTo("MASTER");
        assertThat(field(booking, "prepaymentRequired").asBoolean()).isTrue();
        assertThat(field(booking, "comment").asString()).isEqualTo("Записана по телефону");
    }

    /**
     * Решение о предоплате — обязательное поле, как все поля тел (спецификация API, §4.2): умолчания нет, пропуск —
     * {@code 400} с именем поля, а не молча запись без предоплаты.
     */
    @Test
    void masterBookingWithoutPrepaymentChoiceIsRejected() {
        var body = byMasterBody(
                createClient(uniqueLastName()).id(),
                freeSlot().id(),
                mainService().id(),
                false);
        body.remove("awaitPrepayment");

        var problem = json(createByMaster(signInMaster(), body), HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("awaitPrepayment");
    }

    @Test
    void masterBookingWithoutClientIsRejected() {
        var body = byMasterBody(
                createClient(uniqueLastName()).id(),
                freeSlot().id(),
                mainService().id(),
                false);
        body.remove("clientId");

        var problem = json(createByMaster(signInMaster(), body), HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("clientId");
    }

    @Test
    void clientCannotBookAsMaster() {
        var customer = signInClient();
        var body = byMasterBody(
                actor(customer).requireClientId(),
                freeSlot().id(),
                mainService().id(),
                false);

        expectProblem(createByMaster(customer, body), HttpStatus.NOT_FOUND, "common.not_found");
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

    /** Сотрудник без раздела «Записи» получает честный отказ, а не «не найдено» (спецификация API, §4.6). */
    @Test
    void staffWithoutBookingsRightsIsDenied() {
        var person = signInClient();
        var code =
                "NO_BOOKINGS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var role = access.createRole(
                new CreateRole(code, "Только каталог", Map.of(Section.CATALOG, AccessLevel.READ)), developerActor());
        access.assignRole(person.accountId(), role.id(), developerActor());
        var foreign = bookFor(createClient(uniqueLastName()).id(), false);

        expectProblem(getBooking(person, foreign.id()), HttpStatus.FORBIDDEN, "common.access_denied");
    }

    @Test
    void staffGetsModuleCodeForMissingBooking() {
        var problem = json(getBooking(signInMaster(), UUID.randomUUID()), HttpStatus.NOT_FOUND);

        assertThat(field(problem, "code").asString()).isEqualTo("booking.booking_not_found");
    }

    @Test
    void bookingsRequireSession() {
        var day = freshDay();

        expectProblem(ofPeriod(null, day, day), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
    }

    @Test
    void masterReadsBookingsOfPeriod() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var day = LocalDate.ofInstant(booking.slotStartsAt(), clock.getZone());

        var period = json(ofPeriod(signInMaster(), day, day));

        assertThat(ids(period)).containsExactly(booking.id());
    }

    @Test
    void clientCannotReadBookingsOfPeriod() {
        var day = freshDay();

        expectProblem(ofPeriod(signInClient(), day, day), HttpStatus.NOT_FOUND, "common.not_found");
    }

    @Test
    void periodLongerThan93DaysIsRejected() {
        var from = freshDay();

        var problem = json(ofPeriod(signInMaster(), from, from.plusDays(93)), HttpStatus.BAD_REQUEST);

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
    void lateClientCancellationRequiresPrepayment() {
        var profile = newClientProfile();
        var clientId = actor(signIn(profile)).requireClientId();
        var booking = bookFor(clientId, false);
        var window = Duration.ofHours(bookingSettings.settings().freeCancelWindowHours());
        // Минута после закрытия окна бесплатной отмены; слот ещё не начался, если окно не короче двух минут.
        assertThat(window).isGreaterThan(Duration.ofMinutes(2));
        clock.set(booking.slotStartsAt().minus(window).plus(Duration.ofMinutes(1)));

        var cancelled = json(cancel(signIn(profile), booking.id()));

        assertThat(field(cancelled, "status").asString()).isEqualTo("CANCELLED");
        assertThat(field(cancelled, "cancelReason").asString()).isEqualTo("CLIENT_LATE");
        assertThat(clientQueries.byId(clientId).orElseThrow().prepaymentRequired())
                .isTrue();
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

        var moved = json(reschedule(customer, booking.id(), Map.of("newSlotId", newSlot.id())));

        assertThat(field(moved, "id").asString()).isEqualTo(booking.id().toString());
        assertThat(field(moved, "slotId").asString()).isEqualTo(newSlot.id().toString());
        assertThat(field(moved, "slotStartsAt").asString())
                .isEqualTo(newSlot.startsAt().toString());
    }

    @Test
    void reschedulingToSameSlotNamesTheField() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);

        var problem = json(
                reschedule(signInMaster(), booking.id(), Map.of("newSlotId", booking.slotId())),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        var error = field(problem, "errors").path(0);
        assertThat(field(error, "field").asString()).isEqualTo("newSlotId");
        // Причина модуля, а не неизвестного поля тела: то и другое — 400 с тем же именем поля.
        assertThat(field(error, "reason").asString()).isEqualTo("must differ from the current slot");
    }

    @Test
    void reschedulingWithoutSlotIsRejected() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);

        var problem = json(reschedule(signInMaster(), booking.id(), Map.of()), HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("newSlotId");
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

    private RequestHeadersSpec<?> createByClient(Session session, CreateBooking command) {
        return as(session, csrf(client.post().uri("/api/bookings")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    private RequestHeadersSpec<?> createByMaster(Session session, Map<String, @Nullable Object> body) {
        return as(session, csrf(client.post().uri("/api/bookings/by-master")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> getBooking(Session session, UUID bookingId) {
        return as(session, client.get().uri("/api/bookings/{id}", bookingId));
    }

    /** {@code GET /api/bookings?from=&to=}; без {@code session} — анонимно. */
    private RequestHeadersSpec<?> ofPeriod(@Nullable Session session, LocalDate from, LocalDate to) {
        var spec = client.get().uri("/api/bookings?from={from}&to={to}", from, to);
        return session == null ? spec : as(session, spec);
    }

    private RequestHeadersSpec<?> cancel(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "cancel");
    }

    private RequestHeadersSpec<?> refuseLateArrival(Session session, UUID bookingId) {
        return bookingAction(session, bookingId, "refuse-late-arrival");
    }

    private RequestHeadersSpec<?> reschedule(Session session, UUID bookingId, Map<String, UUID> body) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/reschedule", bookingId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
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

    /** Профиль нового клиента у подставного провайдера: по нему тот же человек входит снова после сдвига часов. */
    private static FakeProfile newClientProfile() {
        var subject = uniqueSubject();
        return new FakeProfile(subject, uniquePhone(), null, "Клиент " + subject, null);
    }
}
