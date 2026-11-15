package gag.nails.notifications.internal.preference;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TelegramChatRepository extends JpaRepository<TelegramChatEntity, UUID> {

    boolean existsByAccountId(UUID accountId);

    /**
     * Отвязывает чат аккаунта; без привязки ничего не делает. Без {@code clearAutomatically}: вызывается и из
     * синхронного слушателя внутри транзакции другого модуля, его управляемые сущности не должны отсоединяться.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from TelegramChatEntity c where c.accountId = :accountId")
    int deleteByAccountId(UUID accountId);
}
