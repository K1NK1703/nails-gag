package gag.nails.schedule.internal.settings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsChangeRepository extends JpaRepository<SettingsChangeEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<SettingsChangeEntity> findAllByOrderByChangedAtDescIdDesc();
}
