package gag.nails.schedule.internal;

import gag.nails.schedule.WeekTemplateView;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "week_template")
class WeekTemplateEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String name;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("weekday")
    private List<WeekTemplateDayEntity> days = new ArrayList<>();

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected WeekTemplateEntity() {}

    private WeekTemplateEntity(String name) {
        this.name = name;
    }

    static WeekTemplateEntity create(String name, Map<DayOfWeek, UUID> days) {
        var template = new WeekTemplateEntity(name);
        template.setDays(days);
        return template;
    }

    /**
     * Заменяет карту рабочих дней, сохраняя строки для дней, которые остались: так не нарушается
     * уникальность {@code (template_id, weekday)} внутри одного flush.
     */
    void setDays(Map<DayOfWeek, UUID> days) {
        this.days.removeIf(day -> !days.containsKey(day.weekday()));
        this.days.forEach(day -> day.setDayTemplateId(days.get(day.weekday())));
        days.forEach((weekday, dayTemplateId) -> {
            if (this.days.stream().noneMatch(day -> day.weekday() == weekday)) {
                this.days.add(new WeekTemplateDayEntity(this, weekday, dayTemplateId));
            }
        });
    }

    void rename(String name) {
        this.name = name;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    Optional<UUID> dayTemplateFor(DayOfWeek weekday) {
        return days.stream()
                .filter(day -> day.weekday() == weekday)
                .map(WeekTemplateDayEntity::dayTemplateId)
                .findFirst();
    }

    /** В порядке дней недели, только рабочие дни. */
    Map<DayOfWeek, UUID> days() {
        var result = new EnumMap<DayOfWeek, UUID>(DayOfWeek.class);
        days.forEach(day -> result.put(day.weekday(), day.dayTemplateId()));
        return Collections.unmodifiableMap(result);
    }

    WeekTemplateView toView() {
        return new WeekTemplateView(id, name, days());
    }
}
