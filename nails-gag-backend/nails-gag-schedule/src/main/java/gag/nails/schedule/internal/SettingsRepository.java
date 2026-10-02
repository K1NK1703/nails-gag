package gag.nails.schedule.internal;

import gag.nails.schedule.internal.error.ScheduleRowMissingException;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsRepository extends JpaRepository<SettingsEntity, UUID> {

    /** Единственная строка настроек; её создаёт seed миграции. */
    default SettingsEntity single() {
        return findAll().stream().findFirst().orElseThrow(() -> new ScheduleRowMissingException("schedule.settings"));
    }
}
