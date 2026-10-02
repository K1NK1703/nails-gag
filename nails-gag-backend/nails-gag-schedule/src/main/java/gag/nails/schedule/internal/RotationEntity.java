package gag.nails.schedule.internal;

import gag.nails.schedule.RotationView;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "schedule", name = "rotation")
class RotationEntity {

    @Id
    private UUID id;

    private LocalDate anchorMonday;
    private int anchorIndex;

    @OneToMany(mappedBy = "rotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal")
    private List<RotationWeekEntity> weeks = new ArrayList<>();

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @UpdateTimestamp
    private Instant updatedAt;

    protected RotationEntity() {}

    void set(List<UUID> weekTemplateIds, LocalDate anchorMonday, int anchorIndex) {
        weeks.clear();
        for (var ordinal = 0; ordinal < weekTemplateIds.size(); ordinal++) {
            weeks.add(new RotationWeekEntity(this, ordinal, weekTemplateIds.get(ordinal)));
        }
        this.anchorMonday = anchorMonday;
        this.anchorIndex = anchorIndex;
    }

    /** Применённая неделя становится якорем: следующий предпросмотр продолжит чередование с неё. */
    void moveAnchor(LocalDate monday, int index) {
        this.anchorMonday = monday;
        this.anchorIndex = index;
    }

    boolean isConfigured() {
        return !weeks.isEmpty();
    }

    List<UUID> weekTemplateIds() {
        return weeks.stream().map(RotationWeekEntity::weekTemplateId).toList();
    }

    /** Индекс шаблона недели для недели с данным понедельником: от якоря вперёд и назад по кругу. */
    int templateIndexFor(LocalDate monday) {
        var weeksFromAnchor = ChronoUnit.WEEKS.between(anchorMonday, monday);
        return Math.floorMod(anchorIndex + weeksFromAnchor, weeks.size());
    }

    RotationView toView() {
        return new RotationView(weekTemplateIds(), anchorMonday, anchorIndex);
    }
}
