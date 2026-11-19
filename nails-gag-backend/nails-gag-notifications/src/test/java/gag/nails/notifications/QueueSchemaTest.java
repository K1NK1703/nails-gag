package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Перечисления очереди и ограничения {@code CHECK} её таблиц не расходятся: каждое значение каждого перечисления
 * ложится в свою колонку, а несогласованные строки база отвергает. Транзакция с откатом.
 */
@NotificationsModuleTest
@Transactional
class QueueSchemaTest {

    private static final Instant DUE = Instant.parse("2026-11-20T09:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @ParameterizedTest
    @EnumSource(MessageKind.class)
    void everyMessageKindIsStored(MessageKind kind) {
        var staff = kind.name().startsWith("STAFF_");

        var id = message(
                kind, MessageStatus.PENDING, staff ? null : UUID.randomUUID(), staff ? UUID.randomUUID() : null);

        assertThat(column("message", "kind", id)).isEqualTo(kind.name());
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    void everyMessageStatusIsStored(MessageStatus status) {
        var id = message(MessageKind.CONFIRMED, status, UUID.randomUUID(), null);

        assertThat(column("message", "status", id)).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(Channel.class)
    void everyChannelIsStored(Channel channel) {
        var id = delivery(channel, DeliveryStatus.PENDING);

        assertThat(column("delivery", "channel", id)).isEqualTo(channel.name());
    }

    @ParameterizedTest
    @EnumSource(DeliveryStatus.class)
    void everyDeliveryStatusIsStored(DeliveryStatus status) {
        var id = delivery(Channel.EMAIL, status);

        assertThat(column("delivery", "status", id)).isEqualTo(status.name());
    }

    @Test
    void staffKindNeedsAccountRecipient() {
        assertThatThrownBy(() -> message(MessageKind.STAFF_NEW_BOOKING, MessageStatus.PENDING, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("message_kind_recipient_check");
    }

    @Test
    void clientKindNeedsClientRecipient() {
        assertThatThrownBy(() -> message(MessageKind.CONFIRMED, MessageStatus.PENDING, null, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("message_kind_recipient_check");
    }

    @Test
    void failedDeliveryNeedsErrorCode() {
        var messageId = message(MessageKind.CONFIRMED, MessageStatus.DISPATCHED, UUID.randomUUID(), null);

        assertThatThrownBy(() -> insertDelivery(messageId, Channel.EMAIL, DeliveryStatus.FAILED, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_error_code_failed_check");
    }

    private UUID message(MessageKind kind, MessageStatus status, @Nullable UUID clientId, @Nullable UUID accountId) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.message (id, client_id, account_id, kind, booking_id, due_at,
                            expires_at, status, dedup_key, created_at, dispatched_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        clientId,
                        accountId,
                        kind.name(),
                        UUID.randomUUID(),
                        Timestamp.from(DUE),
                        Timestamp.from(DUE.plus(Duration.ofDays(1))),
                        status.name(),
                        "test:" + id,
                        Timestamp.from(DUE),
                        status == MessageStatus.DISPATCHED ? Timestamp.from(DUE) : null)
                .update();
        return id;
    }

    /** Доставка в согласованном виде: у отправленной — время отправки, у неудачной — код ошибки. */
    private UUID delivery(Channel channel, DeliveryStatus status) {
        var messageId = message(MessageKind.CONFIRMED, MessageStatus.DISPATCHED, UUID.randomUUID(), null);
        return insertDelivery(
                messageId,
                channel,
                status,
                status == DeliveryStatus.SENT ? DUE : null,
                status == DeliveryStatus.FAILED ? "expired" : null);
    }

    private UUID insertDelivery(
            UUID messageId,
            Channel channel,
            DeliveryStatus status,
            @Nullable Instant sentAt,
            @Nullable String errorCode) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.delivery (id, message_id, channel, status, attempts,
                            next_attempt_at, sent_at, error_code)
                        values (?, ?, ?, ?, 0, ?, ?, ?)
                        """)
                .params(
                        id,
                        messageId,
                        channel.name(),
                        status.name(),
                        Timestamp.from(DUE),
                        sentAt == null ? null : Timestamp.from(sentAt),
                        errorCode)
                .update();
        return id;
    }

    private String column(String table, String column, UUID id) {
        return jdbc.sql("select " + column + " from notifications." + table + " where id = ?")
                .param(id)
                .query(String.class)
                .single();
    }
}
