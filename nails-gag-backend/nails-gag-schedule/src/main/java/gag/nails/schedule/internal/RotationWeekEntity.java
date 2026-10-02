package gag.nails.schedule.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "rotation_week")
class RotationWeekEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rotation_id")
    private RotationEntity rotation;

    private int ordinal;
    private UUID weekTemplateId;

    protected RotationWeekEntity() {}

    RotationWeekEntity(RotationEntity rotation, int ordinal, UUID weekTemplateId) {
        this.rotation = rotation;
        this.ordinal = ordinal;
        this.weekTemplateId = weekTemplateId;
    }

    UUID weekTemplateId() {
        return weekTemplateId;
    }
}
