package gag.nails.schedule.internal;

import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleService implements ScheduleService {

    private final SlotRepository slots;
    private final SettingsRepository settings;
    private final SlotWriter writer;

    DefaultScheduleService(SlotRepository slots, SettingsRepository settings, SlotWriter writer) {
        this.slots = slots;
        this.settings = settings;
        this.writer = writer;
    }

    @Override
    public SlotView addSlot(LocalDate date, LocalTime startTime, Actor actor) {
        actor.requireMaster();
        return writer.create(date, startTime, settings.single().slotDurationMinutes())
                .toView();
    }

    @Override
    public SlotView moveSlot(UUID slotId, LocalTime newStartTime, Actor actor) {
        actor.requireMaster();
        var slot = requireFree(slotId);
        writer.move(slot, newStartTime);
        return slot.toView();
    }

    @Override
    public void deleteSlot(UUID slotId, Actor actor) {
        actor.requireMaster();
        slots.delete(requireFree(slotId));
    }

    private SlotEntity requireFree(UUID slotId) {
        var slot = slots.findById(slotId).orElseThrow(() -> new SlotNotFoundException(slotId));
        if (slot.occupied()) {
            throw new SlotOccupiedException(slotId);
        }
        return slot;
    }
}
