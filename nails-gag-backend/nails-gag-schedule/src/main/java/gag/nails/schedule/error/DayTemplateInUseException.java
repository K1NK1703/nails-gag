package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Шаблон дня используется шаблоном недели: сначала убрать его оттуда. */
public final class DayTemplateInUseException extends UserFacingException {

    public DayTemplateInUseException(UUID templateId) {
        super("schedule.day_template_in_use", Map.of("templateId", templateId));
    }
}
