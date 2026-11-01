package gag.nails.finance.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Ручной записи с таким идентификатором нет (для истории — не было никогда). */
public final class EntryNotFoundException extends UserFacingException {

    public EntryNotFoundException(UUID entryId) {
        super("finance.entry_not_found", Map.of("entryId", entryId));
    }
}
