package gag.nails.booking.internal.settings;

import gag.nails.booking.model.BookingSettingsChangeView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "booking", name = "settings_change")
class BookingSettingsChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String field;
    private String oldValue;
    private String newValue;
    private Instant changedAt;

    protected BookingSettingsChangeEntity() {}

    BookingSettingsChangeEntity(String field, String oldValue, String newValue, Instant changedAt) {
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedAt = changedAt;
    }

    BookingSettingsChangeView toView() {
        return new BookingSettingsChangeView(field, oldValue, newValue, changedAt);
    }
}
