package gag.nails.schedule.internal.template;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "week_template_day")
class WeekTemplateDayEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id")
    private WeekTemplateEntity template;

    private int weekday;
    private UUID dayTemplateId;

    protected WeekTemplateDayEntity() {}

    WeekTemplateDayEntity(WeekTemplateEntity template, DayOfWeek weekday, UUID dayTemplateId) {
        this.template = template;
        this.weekday = weekday.getValue();
        this.dayTemplateId = dayTemplateId;
    }

    DayOfWeek weekday() {
        return DayOfWeek.of(weekday);
    }

    UUID dayTemplateId() {
        return dayTemplateId;
    }

    void setDayTemplateId(UUID dayTemplateId) {
        this.dayTemplateId = dayTemplateId;
    }
}
