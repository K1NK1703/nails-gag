package gag.nails.notifications.internal.preference;

import gag.nails.common.id.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Всё, что модуль хранит об аккаунте для каналов: ссылка привязки, привязка Telegram и настройка каналов.
 *
 * <p>Порядок блокировок по этим таблицам — строка аккаунта ({@code identity.account}) → токен → чат → настройки
 * каналов ({@code link_token} → {@code telegram_chat} → {@code preference}). Строку аккаунта первой держат запись
 * ссылки и настройки каналов ({@code FOR KEY SHARE}, {@code Accounts.lockExisting}) и удаление аккаунта
 * ({@code FOR UPDATE}, до вызова {@link #forget}): данные, записанные во время удаления, не переживают его. Привязка
 * бота по ссылке сначала гасит токен, потом пишет чат. Кто меняет несколько из них в одной транзакции, идёт в этом
 * порядке — иначе встречная привязка может дать взаимную блокировку или оставить чат удалённого аккаунта. Удаление
 * аккаунта ({@link #forget}) начинает с той же строки токена, которую гасит привязка ({@link LinkTokens#consume},
 * затем {@link #bindTelegram}): кто взял её вторым, ждёт первого и видит его итог.
 *
 * <p>Отправка читает здесь настройки каналов и привязки получателей и отвязывает чат, заблокировавший бота. Адрес чата
 * ({@code chat_id}) нужен только каналу Telegram в момент отправки — наружу модуля и в логи он не попадает.
 */
@Component
public class AccountChannels {

    private final JdbcClient jdbc;

    AccountChannels(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Удаляет ссылку привязки, привязку бота и настройку каналов аккаунта (спека §3.10) — в порядке блокировок модуля;
     * чего нет — пропускается. Вызывается из синхронного слушателя в транзакции удаления аккаунта, поэтому запросами
     * JDBC: контекст персистентности источника не сбрасывается и не очищается.
     */
    public void forget(UUID accountId) {
        jdbc.sql("delete from notifications.link_token where account_id = :accountId")
                .param("accountId", accountId)
                .update();
        jdbc.sql("delete from notifications.telegram_chat where account_id = :accountId")
                .param("accountId", accountId)
                .update();
        jdbc.sql("delete from notifications.preference where account_id = :accountId")
                .param("accountId", accountId)
                .update();
    }

    /**
     * Свои настройки каналов аккаунтов из {@code accountIds}; аккаунта без строки в ответе нет — у него умолчания
     * ({@link ChannelDefaults}).
     */
    public Map<UUID, ChannelSwitches> switches(Collection<UUID> accountIds) {
        var switches = new HashMap<UUID, ChannelSwitches>();
        if (accountIds.isEmpty()) {
            return switches;
        }
        jdbc.sql("""
                        select account_id, email_enabled, telegram_enabled from notifications.preference
                        where account_id in (:accountIds)
                        """).param("accountIds", accountIds).query(row -> {
            switches.put(
                    row.getObject("account_id", UUID.class),
                    new ChannelSwitches(row.getBoolean("email_enabled"), row.getBoolean("telegram_enabled")));
        });
        return switches;
    }

    /** Аккаунты из {@code accountIds}, к которым привязан Telegram-бот. */
    public Set<UUID> linkedToTelegram(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
                jdbc.sql("""
                        select account_id from notifications.telegram_chat where account_id in (:accountIds)
                        """).param("accountIds", accountIds).query(UUID.class).list());
    }

    /** Чат Telegram, к которому привязан аккаунт; пусто — бот не привязан. */
    public Optional<Long> telegramChat(UUID accountId) {
        return jdbc.sql("select chat_id from notifications.telegram_chat where account_id = :accountId")
                .param("accountId", accountId)
                .query(Long.class)
                .optional();
    }

    /**
     * Привязывает чат {@code chatId} к аккаунту (спека §3.6): прежний аккаунт этого чата и прежний чат аккаунта
     * отвязываются. Вызывается в транзакции привязки по ссылке после гашения её токена ({@link LinkTokens#consume}) —
     * в порядке блокировок модуля. Два одновременных {@code /start} в один чат (разными ссылками) могут оба не найти
     * строк и вставить по одной — второй получит нарушение уникальности ({@code 23505}); приём сообщений бота один и
     * повторяет обновление после сбоя, повтор проходит.
     */
    public void bindTelegram(UUID accountId, long chatId, Instant linkedAt) {
        jdbc.sql("delete from notifications.telegram_chat where chat_id = :chatId or account_id = :accountId")
                .param("chatId", chatId)
                .param("accountId", accountId)
                .update();
        jdbc.sql("""
                        insert into notifications.telegram_chat (id, account_id, chat_id, linked_at)
                        values (:id, :accountId, :chatId, :linkedAt)
                        """)
                .param("id", UuidV7.next())
                .param("accountId", accountId)
                .param("chatId", chatId)
                .param("linkedAt", Timestamp.from(linkedAt))
                .update();
    }

    /**
     * Отвязывает чат {@code chatId} от аккаунта, к которому он привязан ({@code /stop} в боте); без привязки ничего не
     * делает.
     *
     * @return была ли привязка удалена
     */
    public boolean unlinkChat(long chatId) {
        return jdbc.sql("delete from notifications.telegram_chat where chat_id = :chatId")
                        .param("chatId", chatId)
                        .update()
                > 0;
    }

    /**
     * Отвязывает от аккаунта чат {@code chatId} — только если аккаунт всё ещё привязан именно к нему: привязку, которую
     * клиентка за это время сделала заново к другому чату, не трогает. Из таблиц каналов меняется только чат, поэтому
     * порядок блокировок модуля соблюдён.
     *
     * @return была ли привязка удалена
     */
    public boolean unlinkTelegram(UUID accountId, long chatId) {
        var deleted = jdbc.sql("""
                        delete from notifications.telegram_chat where account_id = :accountId and chat_id = :chatId
                        """)
                .param("accountId", accountId)
                .param("chatId", chatId)
                .update();
        return deleted > 0;
    }
}
