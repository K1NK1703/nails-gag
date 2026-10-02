package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Шаблон с таким именем уже есть. */
public final class TemplateNameTakenException extends UserFacingException {

    public TemplateNameTakenException(String name) {
        super("schedule.template_name_taken", Map.of("name", name));
    }
}
