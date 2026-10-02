package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Шаблона недели с таким идентификатором нет. */
public final class WeekTemplateNotFoundException extends UserFacingException {

    public WeekTemplateNotFoundException(UUID templateId) {
        super("schedule.week_template_not_found", Map.of("templateId", templateId));
    }
}
