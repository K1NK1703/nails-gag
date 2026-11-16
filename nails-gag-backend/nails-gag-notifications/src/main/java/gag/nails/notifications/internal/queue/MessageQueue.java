package gag.nails.notifications.internal.queue;

import gag.nails.common.id.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Очередь сообщений ({@code notifications.message}): постановка и изменения неотправленных ({@code PENDING}).
 * Отправленные и отменённые не меняются — это история. Работает в транзакции вызывающего: асинхронного слушателя
 * событий записи или синхронного слушателя в чужой транзакции. Запросы — напрямую через JDBC: сущности сообщения в
 * контексте персистентности нет, поэтому изменения ничего не отсоединяют и не требуют сброса чужих изменений.
 */
@Component
public class MessageQueue {

    private final JdbcClient jdbc;
    private final Clock clock;

    MessageQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Ставит сообщение в очередь ({@code PENDING}). Сообщение с тем же ключом повтора уже есть — ничего не делает:
     * повтор события после рестарта безопасен, в том числе при двух одновременных обработках.
     *
     * @return поставлено ли сообщение
     */
    public boolean enqueue(NewMessage message) {
        UUID clientId = null;
        UUID accountId = null;
        switch (message.recipient()) {
            case Recipient.Client client -> clientId = client.clientId();
            case Recipient.Account account -> accountId = account.accountId();
        }
        var inserted = jdbc.sql("""
                        insert into notifications.message (id, client_id, account_id, kind, booking_id, due_at,
                            expires_at, status, dedup_key, created_at)
                        values (:id, :clientId, :accountId, :kind, :bookingId, :dueAt, :expiresAt, 'PENDING',
                            :dedupKey, :now)
                        on conflict (dedup_key) do nothing
                        """)
                .param("id", UuidV7.next())
                .param("clientId", clientId)
                .param("accountId", accountId)
                .param("kind", message.kind().name())
                .param("bookingId", message.bookingId())
                .param("dueAt", timestamp(message.dueAt()))
                .param("expiresAt", timestamp(message.expiresAt()))
                .param("dedupKey", message.dedupKey())
                .param("now", timestamp(clock.instant()))
                .update();
        return inserted > 0;
    }

    /** Отменяет неотправленные сообщения карточки клиента; возвращает их число. */
    public int cancelPendingOfClient(UUID clientId) {
        return jdbc.sql("""
                        update notifications.message set status = 'CANCELLED'
                        where client_id = :clientId and status = 'PENDING'
                        """).param("clientId", clientId).update();
    }

    /** Отменяет неотправленные сообщения аккаунта (мастерские); возвращает их число. */
    public int cancelPendingOfAccount(UUID accountId) {
        return jdbc.sql("""
                        update notifications.message set status = 'CANCELLED'
                        where account_id = :accountId and status = 'PENDING'
                        """).param("accountId", accountId).update();
    }

    /**
     * Переадресует неотправленные сообщения карточки {@code removedClientId} карточке {@code survivorClientId};
     * возвращает их число. Ключ повтора не меняется: повтор события удалённой карточки дубля не создаст.
     */
    public int movePending(UUID removedClientId, UUID survivorClientId) {
        return jdbc.sql("""
                        update notifications.message set client_id = :survivorClientId
                        where client_id = :removedClientId and status = 'PENDING'
                        """)
                .param("removedClientId", removedClientId)
                .param("survivorClientId", survivorClientId)
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
