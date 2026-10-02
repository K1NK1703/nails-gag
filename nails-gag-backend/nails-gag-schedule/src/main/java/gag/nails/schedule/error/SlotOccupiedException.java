package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Слот занят записью: его нельзя занять ещё раз, сдвинуть или закрыть. */
public final class SlotOccupiedException extends UserFacingException {

    public SlotOccupiedException(UUID slotId) {
        super("schedule.slot_occupied", Map.of("slotId", slotId));
    }
}
