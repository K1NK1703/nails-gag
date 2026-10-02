package gag.nails.schedule.internal;

import gag.nails.schedule.ScheduleSettingsView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "schedule", name = "settings")
class SettingsEntity {

    @Id
    private UUID id;

    private int slotDurationMinutes;

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @UpdateTimestamp
    private Instant updatedAt;

    protected SettingsEntity() {}

    int slotDurationMinutes() {
        return slotDurationMinutes;
    }

    void setSlotDurationMinutes(int slotDurationMinutes) {
        this.slotDurationMinutes = slotDurationMinutes;
    }

    ScheduleSettingsView toView() {
        return new ScheduleSettingsView(slotDurationMinutes);
    }
}
