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
 *
 * <p>Изменения нескольких строк сначала блокируют их подзапросом {@code … order by id for update}: две транзакции,
 * меняющие пересекающиеся наборы (отмена сообщений карточки и перепланирование напоминаний её записи), берут общие
 * строки в одном порядке и не блокируют друг друга накрест. Порядок блокировок модуля по очереди — в
 * {@code ReminderPlanner}.
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
                        where id in (select id from notifications.message
                            where client_id = :clientId and status = 'PENDING' order by id for update)
                        """).param("clientId", clientId).update();
    }

    /** Отменяет неотправленные сообщения аккаунта (мастерские); возвращает их число. */
    public int cancelPendingOfAccount(UUID accountId) {
        return jdbc.sql("""
                        update notifications.message set status = 'CANCELLED'
                        where id in (select id from notifications.message
                            where account_id = :accountId and status = 'PENDING' order by id for update)
                        """).param("accountId", accountId).update();
    }

    /**
     * Удаляет неотправленные напоминания записи ({@code REMINDER_*}) перед их расчётом заново; возвращает их число.
     * Удаляет, а не отменяет: ключ повтора освобождается, и напоминание на тот же срок (возврат на прежнее время,
     * повторное включение в настройках) ставится снова, а отменённая строка заняла бы ключ навсегда.
     */
    public int deletePendingReminders(UUID bookingId) {
        return jdbc.sql("""
                        delete from notifications.message
                        where id in (select id from notifications.message
                            where booking_id = :bookingId and status = 'PENDING' and starts_with(kind, 'REMINDER_')
                            order by id for update)
                        """).param("bookingId", bookingId).update();
    }

    /**
     * Отменяет неотправленные напоминания записи ({@code REMINDER_*}), когда визита уже не будет: запись отменена,
     * выполнена или клиентка не пришла. Отменённые остаются историей; возвращает их число.
     */
    public int cancelPendingReminders(UUID bookingId) {
        return jdbc.sql("""
                        update notifications.message set status = 'CANCELLED'
                        where id in (select id from notifications.message
                            where booking_id = :bookingId and status = 'PENDING' and starts_with(kind, 'REMINDER_')
                            order by id for update)
                        """).param("bookingId", bookingId).update();
    }

    /**
     * Переадресует неотправленные сообщения карточки {@code removedClientId} карточке {@code survivorClientId};
     * возвращает их число. Ключ повтора не меняется: повтор события удалённой карточки дубля не создаст.
     */
    public int movePending(UUID removedClientId, UUID survivorClientId) {
        return jdbc.sql("""
                        update notifications.message set client_id = :survivorClientId
                        where id in (select id from notifications.message
                            where client_id = :removedClientId and status = 'PENDING' order by id for update)
                        """)
                .param("removedClientId", removedClientId)
                .param("survivorClientId", survivorClientId)
                .update();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
