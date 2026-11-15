package gag.nails.notifications.internal.settings;

import gag.nails.notifications.model.ReminderSettingsChange;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "notifications", name = "settings_change")
class ReminderSettingsChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String field;
    private @Nullable String oldValue;
    private @Nullable String newValue;
    private @Nullable UUID changedBy;
    private Instant changedAt;

    protected ReminderSettingsChangeEntity() {}

    ReminderSettingsChangeEntity(
            String field,
            @Nullable String oldValue,
            @Nullable String newValue,
            @Nullable UUID changedBy,
            Instant changedAt) {
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    ReminderSettingsChange toView() {
        return new ReminderSettingsChange(field, oldValue, newValue, changedBy, changedAt);
    }
}
