package gag.nails.notifications.internal.preference;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Своя настройка каналов аккаунта. Вызывается только из {@link DefaultNotificationPreferences} (HTTP: свои каналы);
 * удаление аккаунта стирает строку не здесь, а запросом {@link AccountChannels#forget}. Запись — вставкой мимо
 * контекста персистентности, поэтому настройка читается проекцией, а не сущностью: проекция всегда берётся из базы, а
 * управляемая сущность, прочитанная раньше в той же транзакции, осталась бы прежней.
 */
interface PreferenceRepository extends JpaRepository<PreferenceEntity, UUID> {

    @Query("select new gag.nails.notifications.internal.preference.ChannelSwitches(p.emailEnabled, p.telegramEnabled)"
            + " from PreferenceEntity p where p.accountId = :accountId")
    Optional<ChannelSwitches> findSwitches(UUID accountId);

    /**
     * Заводит или заменяет настройку аккаунта. {@code ON CONFLICT} вместо проверки в коде: два одновременных первых
     * сохранения не прервут транзакцию нарушением ключа — второе дождётся первого и обновит строку.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
                    insert into notifications.preference (id, account_id, email_enabled, telegram_enabled, updated_at)
                    values (:id, :accountId, :emailEnabled, :telegramEnabled, :now)
                    on conflict (account_id) do update
                    set email_enabled = excluded.email_enabled,
                        telegram_enabled = excluded.telegram_enabled,
                        updated_at = excluded.updated_at
                    """, nativeQuery = true)
    void upsert(UUID id, UUID accountId, boolean emailEnabled, boolean telegramEnabled, Instant now);
}
