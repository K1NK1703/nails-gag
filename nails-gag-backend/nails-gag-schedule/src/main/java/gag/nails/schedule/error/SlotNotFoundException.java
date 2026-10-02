package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Слота с таким идентификатором нет (возможно, его уже закрыли). */
public final class SlotNotFoundException extends UserFacingException {

    public SlotNotFoundException(UUID slotId) {
        super("schedule.slot_not_found", Map.of("slotId", slotId));
    }
}
