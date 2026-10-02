package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.time.Instant;
import java.util.Map;

/** Новый интервал слота пересекается с существующим слотом. */
public final class SlotOverlapException extends UserFacingException {

    public SlotOverlapException(Instant startsAt, Instant endsAt) {
        super("schedule.slot_overlap", Map.of("startsAt", startsAt, "endsAt", endsAt));
    }
}
