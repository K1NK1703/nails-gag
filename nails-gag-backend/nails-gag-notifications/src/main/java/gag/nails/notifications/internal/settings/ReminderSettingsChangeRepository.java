package gag.nails.notifications.internal.settings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReminderSettingsChangeRepository extends JpaRepository<ReminderSettingsChangeEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<ReminderSettingsChangeEntity> findAllByOrderByChangedAtDescIdDesc();
}
