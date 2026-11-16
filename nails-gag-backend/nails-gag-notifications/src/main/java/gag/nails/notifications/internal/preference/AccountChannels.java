package gag.nails.notifications.internal.preference;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Всё, что модуль хранит об аккаунте для каналов: настройка, привязка Telegram и ссылка привязки. */
@Component
public class AccountChannels {

    private final PreferenceRepository preferences;
    private final TelegramChatRepository chats;
    private final JdbcClient jdbc;

    AccountChannels(PreferenceRepository preferences, TelegramChatRepository chats, JdbcClient jdbc) {
        this.preferences = preferences;
        this.chats = chats;
        this.jdbc = jdbc;
    }

    /**
     * Удаляет настройку каналов, привязку бота и ссылку привязки аккаунта (спека §3.10); чего нет — пропускается.
     * Вызывается из синхронного слушателя в транзакции удаления аккаунта. Ссылка привязки — запросом JDBC: своей
     * сущности у неё пока нет (её заводят вместе с приёмом обновлений бота).
     */
    public void forget(UUID accountId) {
        chats.deleteByAccountId(accountId);
        preferences.deleteByAccountId(accountId);
        jdbc.sql("delete from notifications.link_token where account_id = :accountId")
                .param("accountId", accountId)
                .update();
    }
}
