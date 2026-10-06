package gag.nails.schedule.internal;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.SlotView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultScheduleQueries implements ScheduleQueries {

    private final SlotRepository slots;

    DefaultScheduleQueries(SlotRepository slots) {
        this.slots = slots;
    }

    @Override
    public List<SlotView> freeSlots(LocalDate from, LocalDate to) {
        Periods.requireOrdered(from, to);
        return views(slots.findAllByDayBetweenAndOccupiedFalseOrderByStartsAt(from, to));
    }

    @Override
    public List<SlotView> slotsOfDay(LocalDate date, Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        return views(slots.findAllByDayOrderByStartsAt(date));
    }

    @Override
    public List<SlotView> slotsOfPeriod(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        Periods.requireOrdered(from, to);
        return views(slots.findAllByDayBetweenOrderByStartsAt(from, to));
    }

    private static List<SlotView> views(List<SlotEntity> entities) {
        return entities.stream().map(SlotEntity::toView).toList();
    }
}
