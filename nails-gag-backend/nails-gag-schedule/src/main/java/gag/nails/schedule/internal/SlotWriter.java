package gag.nails.schedule.internal;

import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.schedule.error.SlotOverlapException;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Создаёт и сдвигает слоты. Пересечения проверяет база (ограничение {@code slot_no_overlap}),
 * поэтому запись сразу уходит в неё через flush, а нарушение переводится в {@link SlotOverlapException}.
 */
@Component
class SlotWriter {

    private static final String NO_OVERLAP_CONSTRAINT = "slot_no_overlap";

    private final SlotRepository slots;
    private final SalonTimeZone salonTimeZone;

    SlotWriter(SlotRepository slots, SalonTimeZone salonTimeZone) {
        this.slots = slots;
        this.salonTimeZone = salonTimeZone;
    }

    SlotEntity create(LocalDate day, LocalTime startTime, int durationMinutes) {
        var slot = new SlotEntity(day, salonTimeZone.at(day, startTime), durationMinutes);
        try {
            return slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException e) {
            throw overlapOr(e, slot);
        }
    }

    void move(SlotEntity slot, LocalTime newStartTime) {
        slot.moveTo(salonTimeZone.at(slot.day(), newStartTime));
        try {
            slots.flush();
        } catch (DataIntegrityViolationException e) {
            throw overlapOr(e, slot);
        }
    }

    private static RuntimeException overlapOr(DataIntegrityViolationException e, SlotEntity slot) {
        return ConstraintViolations.constraintName(e)
                .filter(NO_OVERLAP_CONSTRAINT::equals)
                .<RuntimeException>map(name -> new SlotOverlapException(slot.startsAt(), slot.endsAt()))
                .orElse(e);
    }
}
