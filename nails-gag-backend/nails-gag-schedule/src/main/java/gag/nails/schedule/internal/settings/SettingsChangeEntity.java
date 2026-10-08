package gag.nails.schedule.internal.settings;

import gag.nails.schedule.model.SettingsChangeView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "settings_change")
class SettingsChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String field;
    private String oldValue;
    private String newValue;
    private Instant changedAt;

    protected SettingsChangeEntity() {}

    SettingsChangeEntity(String field, String oldValue, String newValue, Instant changedAt) {
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedAt = changedAt;
    }

    SettingsChangeView toView() {
        return new SettingsChangeView(field, oldValue, newValue, changedAt);
    }
}
