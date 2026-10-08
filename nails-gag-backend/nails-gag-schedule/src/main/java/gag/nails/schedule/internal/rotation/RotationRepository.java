package gag.nails.schedule.internal.rotation;

import gag.nails.schedule.internal.error.ScheduleRowMissingException;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RotationRepository extends JpaRepository<RotationEntity, UUID> {

    /** Единственная строка ротации; её создаёт seed миграции. */
    default RotationEntity single() {
        return findAll().stream().findFirst().orElseThrow(() -> new ScheduleRowMissingException("schedule.rotation"));
    }

    /** Входит ли шаблон недели в ротацию. */
    @Query("select count(w) > 0 from RotationWeekEntity w where w.weekTemplateId = :weekTemplateId")
    boolean usesWeekTemplate(UUID weekTemplateId);
}
