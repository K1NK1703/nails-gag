package gag.nails.schedule.internal.slot;

import gag.nails.schedule.model.SlotView;
import gag.nails.schedule.model.TemplateApplication;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import org.springframework.stereotype.Component;

/** Открывает дни по плану «дата → времена начала». Дни, где уже есть хотя бы один слот, пропускает. */
@Component
public class DayOpener {

    private final SlotRepository slots;
    private final SlotWriter writer;

    DayOpener(SlotRepository slots, SlotWriter writer) {
        this.slots = slots;
        this.writer = writer;
    }

    public TemplateApplication open(SortedMap<LocalDate, List<LocalTime>> plan, int durationMinutes) {
        if (plan.isEmpty()) {
            return new TemplateApplication(List.of(), List.of());
        }
        var alreadyOpen = Set.copyOf(slots.openDays(plan.keySet()));
        var created = new ArrayList<SlotView>();
        var skipped = new ArrayList<LocalDate>();
        plan.forEach((day, startTimes) -> {
            if (alreadyOpen.contains(day)) {
                skipped.add(day);
                return;
            }
            for (var startTime : startTimes) {
                created.add(writer.create(day, startTime, durationMinutes).toView());
            }
        });
        return new TemplateApplication(List.copyOf(created), List.copyOf(skipped));
    }
}
