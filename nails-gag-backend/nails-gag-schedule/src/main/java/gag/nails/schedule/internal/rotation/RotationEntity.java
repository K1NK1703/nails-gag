package gag.nails.schedule.internal.rotation;

import gag.nails.schedule.error.RotationNotConfiguredException;
import gag.nails.schedule.model.RotationView;
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
public class RotationEntity {

    @Id
    private UUID id;

    private LocalDate anchorMonday;
    private int anchorIndex;

    @OneToMany(mappedBy = "rotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal")
    private List<RotationWeekEntity> weeks = new ArrayList<>();

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    // Меняется только при UPDATE самой строки: правка одной лишь коллекции его не сдвигает.
    @UpdateTimestamp
    private Instant updatedAt;

    protected RotationEntity() {}

    public void set(List<UUID> weekTemplateIds, LocalDate anchorMonday, int anchorIndex) {
        weeks.clear();
        for (var ordinal = 0; ordinal < weekTemplateIds.size(); ordinal++) {
            weeks.add(new RotationWeekEntity(this, ordinal, weekTemplateIds.get(ordinal)));
        }
        this.anchorMonday = anchorMonday;
        this.anchorIndex = anchorIndex;
    }

    /**
     * Применённая неделя становится якорем; если шаблон не переставлен, позиция в ротации сохраняется,
     * иначе берётся первое вхождение выбранного шаблона.
     */
    void moveAnchor(LocalDate monday, UUID chosenTemplateId) {
        var expected = templateIndexFor(monday);
        var ids = weekTemplateIds();
        this.anchorMonday = monday;
        this.anchorIndex = ids.get(expected).equals(chosenTemplateId) ? expected : ids.indexOf(chosenTemplateId);
    }

    boolean isConfigured() {
        return !weeks.isEmpty();
    }

    List<UUID> weekTemplateIds() {
        return weeks.stream().map(RotationWeekEntity::weekTemplateId).toList();
    }

    /** Индекс шаблона недели для недели с данным понедельником: от якоря вперёд и назад по кругу. */
    int templateIndexFor(LocalDate monday) {
        // floorMod по нулю не должен утекать наружу ArithmeticException.
        if (weeks.isEmpty()) {
            throw new RotationNotConfiguredException();
        }
        var weeksFromAnchor = ChronoUnit.WEEKS.between(anchorMonday, monday);
        return Math.floorMod(anchorIndex + weeksFromAnchor, weeks.size());
    }

    public RotationView toView() {
        return new RotationView(weekTemplateIds(), anchorMonday, anchorIndex);
    }
}
