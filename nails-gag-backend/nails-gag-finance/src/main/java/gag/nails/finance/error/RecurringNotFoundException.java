package gag.nails.finance.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Шаблона повторяющейся записи с таким идентификатором нет. */
public final class RecurringNotFoundException extends UserFacingException {

    public RecurringNotFoundException(UUID recurringId) {
        super("finance.recurring_not_found", Map.of("recurringId", recurringId));
    }
}
