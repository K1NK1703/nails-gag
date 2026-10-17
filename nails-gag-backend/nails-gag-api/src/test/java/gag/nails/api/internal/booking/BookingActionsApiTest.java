package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.command.UpdateActualServices;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Переходы записи (спецификация API, §7.6): предоплата, предложение приехать раньше и отметки визита. Права и правила
 * проверяет модуль, здесь — что адрес доходит до нужного вызова и отдаёт запись после перехода.
 */
@ApiTest
class BookingActionsApiTest extends BookingApiTestSupport {

    @Autowired
    EarlierTimeOffers earlierTimeOffers;

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
        var plannedId = booking.plannedServices().getFirst().serviceId();
        var replacement = mainService();
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        var master = signInMaster();

        var completed = json(complete(master, booking.id()));
        assertThat(field(completed, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(completed, "completedAt").asString())
                .isEqualTo(clock.instant().toString());
        assertThat(actualServiceIds(completed)).containsExactly(plannedId);
        assertThat(field(completed, "finalAmount").decimalValue()).isEqualByComparingTo("1500.00");

        var corrected = json(updateActualServices(
                master,
                booking.id(),
                new UpdateActualServices(replacement.id(), List.of(), new BigDecimal("1200.00"))));
        assertThat(actualServiceIds(corrected)).containsExactly(replacement.id());
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
        assertThat(actualServiceIds(noShow)).isEmpty();
        assertThat(field(noShow, "finalAmount").isNull()).isTrue();
    }

    @Test
    void masterCorrectsNoShowToCompletion() {
        var booking = bookFor(createClient(uniqueLastName()).id(), false);
        var plannedId = booking.plannedServices().getFirst().serviceId();
        clock.set(booking.slotStartsAt().plus(Duration.ofMinutes(90)));
        var master = signInMaster();
        json(markNoShow(master, booking.id()));

        var completed = json(complete(master, booking.id()));

        assertThat(field(completed, "status").asString()).isEqualTo("COMPLETED");
        assertThat(field(completed, "noShowAt").isNull()).isTrue();
        assertThat(actualServiceIds(completed)).containsExactly(plannedId);
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

    /** {@code serviceId} фактического состава по порядку; элемент без него — провал теста. */
    private static List<UUID> actualServiceIds(JsonNode booking) {
        return field(booking, "actualServices").values().stream()
                .map(service -> UUID.fromString(field(service, "serviceId").asString()))
                .toList();
    }
}
