package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.event.BookingCompleted;
import gag.nails.booking.event.BookingConfirmed;
import gag.nails.booking.event.BookingNoShow;
import gag.nails.booking.event.BookingRescheduled;
import gag.nails.booking.event.EarlierTimeAccepted;
import gag.nails.booking.event.EarlierTimeDeclined;
import gag.nails.booking.event.EarlierTimeProposed;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.CancelReason;
import gag.nails.common.actor.Actor;
import gag.nails.notifications.model.MessageKind;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Матрица сообщений спеки §5.1: событие записи → строки очереди (вид, получатель, {@code due_at}, {@code expires_at}).
 * Создание, отмена и перенос — настоящими операциями модуля записей, остальные события публикует тест. Мастерские
 * сообщения получает каждый из {@code StaffDirectory.recipients(BOOKINGS, READ)}; среди них всегда есть мастер теста.
 */
@NotificationsModuleTest
class MessageMatrixTest extends QueueTestSupport {

    private static final Instant NOW = TestTimeConfiguration.START;

    /** Начало визита в событиях, которые публикует тест; на очередь не влияет. */
    private static final Instant SLOT = Instant.parse("2026-10-05T07:00:00Z");

    private UUID masterAccount;

    @BeforeEach
    void staffMaster() {
        masterAccount = staffAccount("MASTER");
        assertStaffIncludes(masterAccount);
    }

    @Test
    void clientBookingConfirmedNotifiesClientAndStaff() {
        var anna = client("Анна");

        var booking = bookByClient(anna, slot(MONDAY, "10:00"));

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrderElementsOf(all(
                        toStaff(MessageKind.STAFF_NEW_BOOKING, staff(), NOW),
                        List.of(toClient(MessageKind.BOOKING_RECEIVED, anna, NOW))));
    }

    @Test
    void clientBookingAwaitingPrepaymentAsksClientForPrepaymentAndNotifiesStaff() {
        var maria = clientOwingPrepayment("Мария");

        var booking = bookByClient(maria, slot(MONDAY, "12:00"));

        // BookingCreated пишет только мастеру, клиентке — PrepaymentRequested, опубликованный вместе с ним.
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrderElementsOf(all(
                        toStaff(MessageKind.STAFF_NEW_BOOKING, staff(), NOW),
                        List.of(toClient(MessageKind.PREPAYMENT_REQUESTED, maria, NOW))));
    }

    @Test
    void masterBookingConfirmedNotifiesClientOnly() {
        var olga = client("Ольга");

        var booking = bookByMaster(olga, slot(MONDAY, "14:00"), false);

        assertThat(booking.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(messages(booking.id())).containsExactly(toClient(MessageKind.BOOKED_BY_MASTER, olga, NOW));
    }

    @Test
    void masterBookingAwaitingPrepaymentOnlyAsksClientForPrepayment() {
        var vera = client("Вера");

        var booking = bookByMaster(vera, slot(MONDAY, "16:00"), true);

        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(messages(booking.id())).containsExactly(toClient(MessageKind.PREPAYMENT_REQUESTED, vera, NOW));
    }

    @Test
    void confirmationNotifiesClient() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();

        publish(bookingId, new BookingConfirmed(bookingId, clientId, SLOT, true, NOW));

        assertThat(messages(bookingId)).containsExactly(toClient(MessageKind.CONFIRMED, clientId, NOW));
    }

    @Test
    void clientCancellationNotifiesClientAndStaff() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "10:00"));
        clock.advance(Duration.ofHours(1));
        var cancelledAt = clock.instant();

        processed(bookings.cancel(booking.id(), Actor.client(anna)));

        var staff = staff();
        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrderElementsOf(all(
                        toStaff(MessageKind.STAFF_NEW_BOOKING, staff, NOW),
                        List.of(toClient(MessageKind.BOOKING_RECEIVED, anna, NOW)),
                        toStaff(MessageKind.STAFF_CLIENT_CANCELLED, staff, cancelledAt),
                        List.of(toClient(MessageKind.CANCELLED_BY_CLIENT, anna, cancelledAt))));
    }

    @Test
    void masterCancellationNotifiesClientOnly() {
        var olga = client("Ольга");
        var booking = bookByMaster(olga, slot(MONDAY, "14:00"), false);
        clock.advance(Duration.ofHours(1));
        var cancelledAt = clock.instant();

        processed(bookings.cancel(booking.id(), master));

        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrder(
                        toClient(MessageKind.BOOKED_BY_MASTER, olga, NOW),
                        toClient(MessageKind.CANCELLED, olga, cancelledAt));
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void cancellationByReason(CancelReason reason) {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();

        publish(bookingId, new BookingCancelled(bookingId, clientId, SLOT, reason, NOW));

        var expected = switch (reason) {
            case CLIENT, CLIENT_LATE ->
                all(
                        toStaff(MessageKind.STAFF_CLIENT_CANCELLED, staff(), NOW),
                        List.of(toClient(MessageKind.CANCELLED_BY_CLIENT, clientId, NOW)));
            case MASTER, LATE_ARRIVAL, ACCOUNT_DELETED -> List.of(toClient(MessageKind.CANCELLED, clientId, NOW));
            case PREPAYMENT_TIMEOUT ->
                all(
                        toStaff(MessageKind.STAFF_PREPAYMENT_TIMEOUT, staff(), NOW),
                        List.of(toClient(MessageKind.CANCELLED, clientId, NOW)));
        };
        assertThat(messages(bookingId)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void clientRescheduleNotifiesClientAndStaff() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "10:00"));
        var later = slot(TUESDAY, "10:00");
        clock.advance(Duration.ofHours(1));
        var rescheduledAt = clock.instant();

        processed(bookings.reschedule(booking.id(), later.id(), Actor.client(anna)));

        var staff = staff();
        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrderElementsOf(all(
                        toStaff(MessageKind.STAFF_NEW_BOOKING, staff, NOW),
                        List.of(toClient(MessageKind.BOOKING_RECEIVED, anna, NOW)),
                        toStaff(MessageKind.STAFF_CLIENT_RESCHEDULED, staff, rescheduledAt),
                        List.of(toClient(MessageKind.RESCHEDULED, anna, rescheduledAt))));
    }

    @Test
    void masterRescheduleNotifiesClientOnly() {
        var olga = client("Ольга");
        var booking = bookByMaster(olga, slot(MONDAY, "14:00"), false);
        var later = slot(TUESDAY, "14:00");
        clock.advance(Duration.ofHours(1));
        var rescheduledAt = clock.instant();

        processed(bookings.reschedule(booking.id(), later.id(), master));

        assertThat(messages(booking.id()))
                .containsExactlyInAnyOrder(
                        toClient(MessageKind.BOOKED_BY_MASTER, olga, NOW),
                        toClient(MessageKind.RESCHEDULED, olga, rescheduledAt));
    }

    @Test
    void rescheduleWithoutInitiatorCountsAsMaster() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();
        var event = new BookingRescheduled(
                bookingId, clientId, UUID.randomUUID(), SLOT, UUID.randomUUID(), SLOT.plus(Duration.ofDays(1)), NOW);

        publish(bookingId, event);

        assertThat(messages(bookingId)).containsExactly(toClient(MessageKind.RESCHEDULED, clientId, NOW));
    }

    @Test
    void earlierTimeProposalNotifiesClient() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();

        publish(bookingId, new EarlierTimeProposed(bookingId, clientId, SLOT, SLOT.minus(Duration.ofHours(1)), NOW));

        assertThat(messages(bookingId)).containsExactly(toClient(MessageKind.EARLIER_PROPOSED, clientId, NOW));
    }

    @Test
    void earlierTimeAcceptanceNotifiesStaff() {
        var bookingId = bookingId();

        publish(
                bookingId,
                new EarlierTimeAccepted(bookingId, UUID.randomUUID(), SLOT, SLOT.minus(Duration.ofHours(1)), NOW));

        assertThat(messages(bookingId))
                .containsExactlyInAnyOrderElementsOf(toStaff(MessageKind.STAFF_EARLIER_ACCEPTED, staff(), NOW));
    }

    @Test
    void earlierTimeDeclineNotifiesStaff() {
        var bookingId = bookingId();

        publish(bookingId, new EarlierTimeDeclined(bookingId, UUID.randomUUID(), SLOT, NOW));

        assertThat(messages(bookingId))
                .containsExactlyInAnyOrderElementsOf(toStaff(MessageKind.STAFF_EARLIER_DECLINED, staff(), NOW));
    }

    @Test
    void completionAndNoShowNotifyNobody() {
        var completed = bookingId();
        var noShow = bookingId();

        publish(completed, new BookingCompleted(completed, UUID.randomUUID(), SLOT, NOW));
        publish(noShow, new BookingNoShow(noShow, UUID.randomUUID(), SLOT, NOW));

        assertThat(messages(completed)).isEmpty();
        assertThat(messages(noShow)).isEmpty();
    }

    @Test
    void duplicateEventCreatesNoDuplicate() {
        var bookingId = bookingId();
        var event = new BookingCancelled(bookingId, UUID.randomUUID(), SLOT, CancelReason.CLIENT, NOW);
        publish(bookingId, event);
        var first = messages(bookingId);

        publish(bookingId, event);

        assertThat(first).hasSize(staff().size() + 1);
        assertThat(messages(bookingId)).containsExactlyInAnyOrderElementsOf(first);
    }

    @Test
    void dedupKeyNamesEventBookingMomentAndRecipient() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();

        publish(bookingId, new BookingCancelled(bookingId, clientId, SLOT, CancelReason.PREPAYMENT_TIMEOUT, NOW));

        var prefix = "BookingCancelled:" + bookingId + ":2026-10-01T06:00:00Z:";
        assertThat(dedupKeys(bookingId))
                .contains(prefix + "client:" + clientId, prefix + "account:" + masterAccount)
                .allSatisfy(key -> assertThat(key).startsWith(prefix).hasSizeLessThanOrEqualTo(255));
    }

    @Test
    void clientMessagesRespectQuietHours() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();
        // 23:30 по Москве — тихие часы: клиентке — в 9:00 следующего дня, живёт сутки от переноса.
        var late = Instant.parse("2026-11-20T20:30:00Z");
        var morning = Instant.parse("2026-11-21T06:00:00Z");

        publish(bookingId, new BookingCancelled(bookingId, clientId, SLOT, CancelReason.CLIENT, late));

        assertThat(messages(bookingId))
                .filteredOn(message -> message.clientId() != null)
                .containsExactly(toClient(MessageKind.CANCELLED_BY_CLIENT, clientId, morning));
    }

    @Test
    void staffMessagesIgnoreQuietHours() {
        var bookingId = bookingId();
        // 23:30 по Москве: мастеру — сразу.
        var late = Instant.parse("2026-11-20T20:30:00Z");

        publish(bookingId, new BookingCancelled(bookingId, UUID.randomUUID(), SLOT, CancelReason.CLIENT, late));

        assertThat(messages(bookingId))
                .filteredOn(message -> message.accountId() != null)
                .containsExactlyInAnyOrderElementsOf(toStaff(MessageKind.STAFF_CLIENT_CANCELLED, staff(), late));
    }
}
