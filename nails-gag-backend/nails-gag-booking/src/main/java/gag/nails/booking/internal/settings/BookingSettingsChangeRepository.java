package gag.nails.booking.internal.settings;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BookingSettingsChangeRepository extends JpaRepository<BookingSettingsChangeEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<BookingSettingsChangeEntity> findAllByOrderByChangedAtDescIdDesc();
}
