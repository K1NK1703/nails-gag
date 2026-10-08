package gag.nails.schedule.internal.slot;

import gag.nails.common.error.InternalException;
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
 * Flush на каждый слот намеренный: так {@code SlotOverlapException} называет именно тот интервал,
 * который не поместился (применение шаблона — порядка 150 одиночных вставок, для ежемесячного
 * действия мастера это приемлемо). После {@code SlotOverlapException} транзакция PostgreSQL уже
 * прервана: вызывающий код даёт исключению всплыть и никогда не продолжает работу в той же
 * транзакции, поймав его.
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

    /** Любое другое нарушение — ошибка в коде или схеме: наружу уходит только своё исключение. */
    private static RuntimeException overlapOr(DataIntegrityViolationException e, SlotEntity slot) {
        var name = ConstraintViolations.constraintName(e);
        if (name.filter(NO_OVERLAP_CONSTRAINT::equals).isPresent()) {
            return new SlotOverlapException(slot.startsAt(), slot.endsAt());
        }
        return new InternalException("slot write violated constraint " + name.orElse("<unknown>"), e);
    }
}
