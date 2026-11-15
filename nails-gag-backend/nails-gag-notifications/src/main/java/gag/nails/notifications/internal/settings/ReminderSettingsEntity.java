package gag.nails.notifications.internal.settings;

import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.ReminderSettings;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Единственная строка настроек напоминаний; её создаёт миграция. */
@Entity
@Table(schema = "notifications", name = "settings")
class ReminderSettingsEntity {

    @Id
    private UUID id;

    private @Nullable LocalTime reminderDayBeforeAt;
    private @Nullable Integer reminderHoursBefore;
    private Instant updatedAt;

    protected ReminderSettingsEntity() {}

    void apply(UpdateReminderSettings command, Instant now) {
        this.reminderDayBeforeAt = command.dayBeforeAt();
        this.reminderHoursBefore = command.hoursBefore();
        this.updatedAt = now;
    }

    ReminderSettings toView() {
        return new ReminderSettings(reminderDayBeforeAt, reminderHoursBefore);
    }
}
