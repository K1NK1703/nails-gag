package gag.nails.notifications.internal.preference;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Всё, что модуль хранит об аккаунте для каналов: ссылка привязки, привязка Telegram и настройка каналов.
 *
 * <p>Порядок блокировок модуля по этим таблицам — токен → чат → настройки каналов ({@code link_token} →
 * {@code telegram_chat} → {@code preference}): привязка бота по ссылке сначала гасит токен, потом пишет чат. Кто
 * меняет несколько из них в одной транзакции, идёт в этом порядке — иначе встречная привязка может дать взаимную
 * блокировку или оставить чат удалённого аккаунта.
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
}
