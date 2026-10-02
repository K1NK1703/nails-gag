package gag.nails.schedule.internal;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.ScheduleSettingsService;
import gag.nails.schedule.ScheduleSettingsView;
import gag.nails.schedule.SettingsChangeView;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleSettingsService implements ScheduleSettingsService {

    static final String SLOT_DURATION_FIELD = "slot_duration_minutes";
    private static final int MIN_DURATION = 15;
    private static final int MAX_DURATION = 720;

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
        actor.requireMaster();
        if (minutes < MIN_DURATION || minutes > MAX_DURATION) {
            throw new ValidationException("minutes", "must be between " + MIN_DURATION + " and " + MAX_DURATION);
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
        actor.requireMaster();
        return changes.findAllByOrderByChangedAtDescIdDesc().stream()
                .map(SettingsChangeEntity::toView)
                .toList();
    }
}
