package gag.nails.notifications.internal.preference;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Привязка Telegram-бота к аккаунту. {@code chat_id} наружу и в логи не попадает. */
@Entity
@Table(schema = "notifications", name = "telegram_chat")
class TelegramChatEntity {

    @Id
    private UUID id;

    private UUID accountId;
    private long chatId;
    private Instant linkedAt;

    protected TelegramChatEntity() {}
}
