package gag.nails.schedule.internal.settings;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.ScheduleSettingsService;
import gag.nails.schedule.model.ScheduleSettingsView;
import gag.nails.schedule.model.SettingsChangeView;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleSettingsService implements ScheduleSettingsService {

    private static final String SLOT_DURATION_FIELD = "slot_duration_minutes";

    private final SettingsRepository settings;
    private final SettingsChangeRepository changes;
    private final Clock clock;

    DefaultScheduleSettingsService(SettingsRepository settings, SettingsChangeRepository changes, Clock clock) {
        this.settings = settings;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ScheduleSettingsView settings() {
        return settings.single().toView();
    }

    @Override
    public ScheduleSettingsView setSlotDuration(int minutes, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        if (minutes < MIN_SLOT_DURATION_MINUTES || minutes > MAX_SLOT_DURATION_MINUTES) {
            throw new ValidationException(
                    "minutes", "must be between " + MIN_SLOT_DURATION_MINUTES + " and " + MAX_SLOT_DURATION_MINUTES);
        }
        var current = settings.single();
        var old = current.slotDurationMinutes();
        if (old != minutes) {
            current.setSlotDurationMinutes(minutes);
            changes.save(new SettingsChangeEntity(
                    SLOT_DURATION_FIELD, String.valueOf(old), String.valueOf(minutes), clock.instant()));
        }
        return current.toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettingsChangeView> history(Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        return changes.findAllByOrderByChangedAtDescIdDesc().stream()
                .map(SettingsChangeEntity::toView)
                .toList();
    }
}
