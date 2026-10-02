package gag.nails.schedule.internal;

import gag.nails.schedule.DayTemplateView;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "schedule", name = "day_template")
class DayTemplateEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String name;

    @Column(name = "is_default")
    private boolean defaultTemplate;

    private int sortOrder;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startTime")
    private List<DayTemplateItemEntity> items = new ArrayList<>();

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    // Меняется только при UPDATE самой строки: правка одной лишь коллекции его не сдвигает.
    @UpdateTimestamp
    private Instant updatedAt;

    protected DayTemplateEntity() {}

    private DayTemplateEntity(String name, int sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    static DayTemplateEntity create(String name, List<LocalTime> startTimes, int sortOrder) {
        var template = new DayTemplateEntity(name, sortOrder);
        template.setStartTimes(startTimes);
        return template;
    }

    /**
     * Заменяет набор времён, сохраняя строки для времён, которые остались: так не нарушается
     * уникальность {@code (template_id, start_time)} внутри одного flush.
     */
    void setStartTimes(List<LocalTime> startTimes) {
        var wanted = new TreeSet<>(startTimes);
        items.removeIf(item -> !wanted.contains(item.startTime()));
        items.forEach(item -> wanted.remove(item.startTime()));
        wanted.forEach(time -> items.add(new DayTemplateItemEntity(this, time)));
    }

    void rename(String name) {
        this.name = name;
    }

    void setDefault(boolean defaultTemplate) {
        this.defaultTemplate = defaultTemplate;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    int sortOrder() {
        return sortOrder;
    }

    List<LocalTime> startTimes() {
        return items.stream().map(DayTemplateItemEntity::startTime).sorted().toList();
    }

    DayTemplateView toView() {
        return new DayTemplateView(id, name, defaultTemplate, sortOrder, startTimes());
    }
}
