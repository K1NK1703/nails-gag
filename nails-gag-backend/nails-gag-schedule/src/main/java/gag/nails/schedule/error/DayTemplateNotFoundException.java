package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Шаблона дня с таким идентификатором нет. */
public final class DayTemplateNotFoundException extends UserFacingException {

    public DayTemplateNotFoundException(UUID templateId) {
        super("schedule.day_template_not_found", Map.of("templateId", templateId));
    }
}
