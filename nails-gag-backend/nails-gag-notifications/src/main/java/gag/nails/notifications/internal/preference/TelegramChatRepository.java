package gag.nails.notifications.internal.preference;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface TelegramChatRepository extends JpaRepository<TelegramChatEntity, UUID> {

    boolean existsByAccountId(UUID accountId);

    /**
     * Отвязывает чат аккаунта по его просьбе ({@code DELETE /api/notifications/me/telegram},
     * {@link DefaultNotificationPreferences#unlinkTelegram}); без привязки ничего не делает. Удаление аккаунта стирает
     * чат не здесь, а запросом {@link AccountChannels#forget}.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from TelegramChatEntity c where c.accountId = :accountId")
    int deleteByAccountId(UUID accountId);
}
