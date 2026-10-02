package gag.nails.schedule.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "day_template_item")
class DayTemplateItemEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private DayTemplateEntity template;

    private LocalTime startTime;

    protected DayTemplateItemEntity() {}

    DayTemplateItemEntity(DayTemplateEntity template, LocalTime startTime) {
        this.template = template;
        this.startTime = startTime;
    }

    LocalTime startTime() {
        return startTime;
    }
}
