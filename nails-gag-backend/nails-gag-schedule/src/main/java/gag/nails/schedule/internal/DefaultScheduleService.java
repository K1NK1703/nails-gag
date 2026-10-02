package gag.nails.schedule.internal;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.TemplateApplication;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleService implements ScheduleService {

    private final SlotRepository slots;
    private final SettingsRepository settings;
    private final DayTemplateRepository dayTemplates;
    private final SlotWriter writer;
    private final DayOpener opener;

    DefaultScheduleService(
            SlotRepository slots,
            SettingsRepository settings,
            DayTemplateRepository dayTemplates,
            SlotWriter writer,
            DayOpener opener) {
        this.slots = slots;
        this.settings = settings;
        this.dayTemplates = dayTemplates;
        this.writer = writer;
        this.opener = opener;
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

    @Override
    public TemplateApplication applyDayTemplate(UUID templateId, List<LocalDate> dates, Actor actor) {
        actor.requireMaster();
        if (dates.isEmpty()) {
            throw new ValidationException("dates", "must not be empty");
        }
        var template =
                dayTemplates.findById(templateId).orElseThrow(() -> new DayTemplateNotFoundException(templateId));
        var duration = settings.single().slotDurationMinutes();
        var startTimes = template.startTimes();
        TemplateTimes.requireNoOverlap(startTimes, duration);
        var plan = new TreeMap<LocalDate, List<LocalTime>>();
        dates.forEach(date -> plan.put(date, startTimes));
        return opener.open(plan, duration);
    }

    private SlotEntity requireFree(UUID slotId) {
        var slot = slots.findById(slotId).orElseThrow(() -> new SlotNotFoundException(slotId));
        if (slot.occupied()) {
            throw new SlotOccupiedException(slotId);
        }
        return slot;
    }
}
