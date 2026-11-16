package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import gag.nails.testsupport.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Уборка очереди (спека §3.11): уборка работает в своей транзакции, поэтому тест фиксирует свои строки — вставляет их
 * напрямую — и удаляет оставшиеся после себя. Срок хранения — 90 дней; часы — 1 октября 2026.
 */
@NotificationsModuleTest
class NotificationMaintenanceTest {

    @Autowired
    NotificationMaintenance maintenance;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    MutableClock clock;

    private final List<UUID> messageIds = new ArrayList<>();
    private final List<UUID> accountIds = new ArrayList<>();

    @AfterEach
    void removeCommitted() {
        if (!messageIds.isEmpty()) {
            jdbc.sql("delete from notifications.message where id in (:ids)")
                    .param("ids", messageIds)
                    .update();
        }
        if (!accountIds.isEmpty()) {
            jdbc.sql("delete from notifications.link_token where account_id in (:ids)")
                    .param("ids", accountIds)
                    .update();
        }
    }

    @Test
    void finishedMessagesOlderThanRetentionAreRemovedWithDeliveries() {
        var old = clock.instant().minus(Duration.ofDays(91));
        var dispatched = message(MessageStatus.DISPATCHED, old, old);
        var sent = delivery(dispatched, Channel.EMAIL, DeliveryStatus.SENT);
        var failed = delivery(dispatched, Channel.TELEGRAM, DeliveryStatus.FAILED);
        var cancelled = message(MessageStatus.CANCELLED, old, old);

        var removed = maintenance.purgeExpired();

        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(exists("message", dispatched)).isFalse();
        assertThat(exists("message", cancelled)).isFalse();
        assertThat(exists("delivery", sent)).isFalse();
        assertThat(exists("delivery", failed)).isFalse();
    }

    @Test
    void messagesWithinRetentionStay() {
        var recent = clock.instant().minus(Duration.ofDays(89));
        var dispatched = message(MessageStatus.DISPATCHED, recent, recent);

        maintenance.purgeExpired();

        assertThat(exists("message", dispatched)).isTrue();
    }

    /** Отсчёт от срока отправки: напоминание, созданное давно, но отправленное недавно, остаётся. */
    @Test
    void oldReminderWithRecentDueDateStays() {
        var createdLongAgo = clock.instant().minus(Duration.ofDays(120));
        var recentlyDue = clock.instant().minus(Duration.ofDays(1));
        var reminder = message(MessageStatus.DISPATCHED, recentlyDue, createdLongAgo);

        maintenance.purgeExpired();

        assertThat(exists("message", reminder)).isTrue();
    }

    @Test
    void messageWithPendingDeliveryStays() {
        var old = clock.instant().minus(Duration.ofDays(91));
        var dispatched = message(MessageStatus.DISPATCHED, old, old);
        var pending = delivery(dispatched, Channel.EMAIL, DeliveryStatus.PENDING);
        delivery(dispatched, Channel.TELEGRAM, DeliveryStatus.SENT);

        maintenance.purgeExpired();

        assertThat(exists("message", dispatched)).isTrue();
        assertThat(exists("delivery", pending)).isTrue();
    }

    @Test
    void pendingMessageStays() {
        var old = clock.instant().minus(Duration.ofDays(91));
        var pending = message(MessageStatus.PENDING, old, old);

        maintenance.purgeExpired();

        assertThat(exists("message", pending)).isTrue();
    }

    @Test
    void expiredLinkTokensAreRemoved() {
        var expired = linkToken(clock.instant().minusSeconds(1));
        var live = linkToken(clock.instant().plus(Duration.ofMinutes(5)));

        maintenance.purgeExpired();

        assertThat(exists("link_token", expired)).isFalse();
        assertThat(exists("link_token", live)).isTrue();
    }

    @Test
    void purgeRunsOutsideTransactionOnly() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> maintenance.purgeExpired()))
                .isInstanceOf(InternalException.class);
    }

    private UUID message(MessageStatus status, Instant dueAt, Instant createdAt) {
        var id = UUID.randomUUID();
        messageIds.add(id);
        jdbc.sql("""
                        insert into notifications.message (id, client_id, kind, booking_id, due_at, expires_at, status,
                            dedup_key, created_at, dispatched_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        UUID.randomUUID(),
                        MessageKind.REMINDER_DAY_BEFORE.name(),
                        UUID.randomUUID(),
                        Timestamp.from(dueAt),
                        Timestamp.from(dueAt.plus(Duration.ofDays(1))),
                        status.name(),
                        "test:" + id,
                        Timestamp.from(createdAt),
                        status == MessageStatus.DISPATCHED ? Timestamp.from(dueAt) : null)
                .update();
        return id;
    }

    private UUID delivery(UUID messageId, Channel channel, DeliveryStatus status) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.delivery (id, message_id, channel, status, attempts,
                            next_attempt_at, sent_at, error_code)
                        values (?, ?, ?, ?, 1, ?, ?, ?)
                        """)
                .params(
                        id,
                        messageId,
                        channel.name(),
                        status.name(),
                        Timestamp.from(clock.instant()),
                        status == DeliveryStatus.SENT ? Timestamp.from(clock.instant()) : null,
                        errorCode(status))
                .update();
        return id;
    }

    private static @Nullable String errorCode(DeliveryStatus status) {
        return status == DeliveryStatus.FAILED ? "expired" : null;
    }

    private UUID linkToken(Instant expiresAt) {
        var id = UUID.randomUUID();
        var accountId = UUID.randomUUID();
        accountIds.add(accountId);
        var hash = new byte[32];
        ThreadLocalRandom.current().nextBytes(hash);
        jdbc.sql("insert into notifications.link_token (id, account_id, token_hash, expires_at) values (?, ?, ?, ?)")
                .params(id, accountId, hash, Timestamp.from(expiresAt))
                .update();
        return id;
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("select count(*) from notifications." + table + " where id = ?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
