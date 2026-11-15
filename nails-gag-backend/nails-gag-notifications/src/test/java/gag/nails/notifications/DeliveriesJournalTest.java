package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.Actor;
import gag.nails.common.id.UuidV7;
import gag.nails.common.paging.Paging;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.MessageKind;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Журнал доставок читает очередь как есть: сообщения и доставки здесь вставляются напрямую, отбор — по своей записи.
 * Транзакция с откатом.
 */
@NotificationsModuleTest
@Transactional
class DeliveriesJournalTest {

    private static final Instant DUE = Instant.parse("2026-11-20T09:00:00Z");

    @Autowired
    NotificationSettings settings;

    @Autowired
    JdbcClient jdbc;

    @Test
    void journalListsDeliveriesOfBookingNewestFirst() {
        var bookingId = UUID.randomUUID();
        var clientId = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        var earlier = message(bookingId, clientId, null, MessageKind.BOOKING_RECEIVED, DUE);
        var later = message(bookingId, null, accountId, MessageKind.STAFF_NEW_BOOKING, DUE.plusSeconds(60));
        var sentAt = DUE.plusSeconds(5);
        var sent = delivery(earlier, Channel.EMAIL, DeliveryStatus.SENT, 1, null, sentAt);
        var failed = delivery(earlier, Channel.TELEGRAM, DeliveryStatus.FAILED, 5, "telegram_blocked", null);
        var pending = delivery(later, Channel.TELEGRAM, DeliveryStatus.PENDING, 0, null, null);

        var page = settings.deliveries(new DeliveryFilter(null, bookingId), new Paging(0, 20), Actor.master());

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items().getFirst())
                .isEqualTo(new DeliveryView(
                        pending,
                        later,
                        MessageKind.STAFF_NEW_BOOKING,
                        bookingId,
                        null,
                        accountId,
                        Channel.TELEGRAM,
                        DeliveryStatus.PENDING,
                        0,
                        null,
                        DUE.plusSeconds(60),
                        null));
        assertThat(page.items())
                .extracting(DeliveryView::deliveryId)
                .containsExactly(pending, max(sent, failed), min(sent, failed));
        assertThat(page.items())
                .filteredOn(view -> view.deliveryId().equals(sent))
                .singleElement()
                .isEqualTo(new DeliveryView(
                        sent,
                        earlier,
                        MessageKind.BOOKING_RECEIVED,
                        bookingId,
                        clientId,
                        null,
                        Channel.EMAIL,
                        DeliveryStatus.SENT,
                        1,
                        null,
                        DUE,
                        sentAt));
    }

    @Test
    void journalFiltersByStatusAndPages() {
        var bookingId = UUID.randomUUID();
        var messageId = message(bookingId, UUID.randomUUID(), null, MessageKind.CONFIRMED, DUE);
        var sent = delivery(messageId, Channel.EMAIL, DeliveryStatus.SENT, 1, null, DUE);
        delivery(messageId, Channel.TELEGRAM, DeliveryStatus.FAILED, 5, "expired", null);

        var onlySent = settings.deliveries(
                new DeliveryFilter(DeliveryStatus.SENT, bookingId), new Paging(0, 20), Actor.developer());
        var secondPage = settings.deliveries(new DeliveryFilter(null, bookingId), new Paging(1, 1), Actor.developer());

        assertThat(onlySent.items()).extracting(DeliveryView::deliveryId).containsExactly(sent);
        assertThat(onlySent.total()).isEqualTo(1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(secondPage.page()).isEqualTo(1);
        assertThat(secondPage.size()).isEqualTo(1);
    }

    private UUID message(UUID bookingId, UUID clientId, UUID accountId, MessageKind kind, Instant dueAt) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.message (id, client_id, account_id, kind, booking_id, due_at,
                            expires_at, status, dedup_key, created_at, dispatched_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'DISPATCHED', ?, ?, ?)
                        """)
                .params(
                        id,
                        clientId,
                        accountId,
                        kind.name(),
                        bookingId,
                        Timestamp.from(dueAt),
                        Timestamp.from(dueAt.plus(Duration.ofDays(1))),
                        "test:" + id,
                        Timestamp.from(dueAt),
                        Timestamp.from(dueAt))
                .update();
        return id;
    }

    private UUID delivery(
            UUID messageId, Channel channel, DeliveryStatus status, int attempts, String errorCode, Instant sentAt) {
        // UUID v7: обе половины одного знака, поэтому порядок compareTo в Java совпадает с порядком uuid в PostgreSQL.
        var id = UuidV7.next();
        jdbc.sql("""
                        insert into notifications.delivery (id, message_id, channel, status, attempts,
                            next_attempt_at, sent_at, error_code)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        messageId,
                        channel.name(),
                        status.name(),
                        attempts,
                        Timestamp.from(DUE),
                        sentAt == null ? null : Timestamp.from(sentAt),
                        errorCode)
                .update();
        return id;
    }

    private static UUID max(UUID first, UUID second) {
        return first.compareTo(second) > 0 ? first : second;
    }

    private static UUID min(UUID first, UUID second) {
        return first.compareTo(second) > 0 ? second : first;
    }
}
