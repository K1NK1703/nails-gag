package gag.nails.portfolio.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Работы с таким идентификатором нет. */
public final class WorkNotFoundException extends UserFacingException {

    public WorkNotFoundException(UUID workId) {
        super("portfolio.work_not_found", Map.of("workId", workId));
    }
}
