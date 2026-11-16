package gag.nails.notifications.internal.settings;

import gag.nails.common.error.InternalException;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReminderSettingsRepository extends JpaRepository<ReminderSettingsEntity, UUID> {

    /** Единственная строка настроек; её создаёт миграция. */
    default ReminderSettingsEntity single() {
        return findAll().stream().findFirst().orElseThrow(ReminderSettingsRepository::missing);
    }

    /**
     * Та же строка под блокировкой до конца транзакции: два одновременных изменения идут друг за другом, и журнал
     * второго видит значения первого как прежние.
     */
    default ReminderSettingsEntity singleForUpdate() {
        return lockAll().stream().findFirst().orElseThrow(ReminderSettingsRepository::missing);
    }

    /**
     * Та же строка под разделяемой блокировкой ({@code FOR SHARE}) до конца транзакции: планировщик напоминаний
     * читает настройки, а смена настроек ждёт его фиксации (и он — её), поэтому напоминания по прежним настройкам не
     * фиксируются после смены.
     */
    default ReminderSettingsEntity singleForShare() {
        return lockAllForShare().stream().findFirst().orElseThrow(ReminderSettingsRepository::missing);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ReminderSettingsEntity s")
    List<ReminderSettingsEntity> lockAll();

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from ReminderSettingsEntity s")
    List<ReminderSettingsEntity> lockAllForShare();

    private static InternalException missing() {
        return new InternalException("notifications.settings row is missing");
    }
}
