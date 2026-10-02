package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Шаблон недели входит в ротацию: сначала изменить ротацию. */
public final class WeekTemplateInUseException extends UserFacingException {

    public WeekTemplateInUseException(UUID templateId) {
        super("schedule.week_template_in_use", Map.of("templateId", templateId));
    }
}
