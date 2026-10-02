package gag.nails.schedule.error;

import gag.nails.common.error.UserFacingException;
import java.time.LocalTime;
import java.util.Map;

/** При текущей длительности слота два времени шаблона дня пересекаются. */
public final class TemplateOverlapException extends UserFacingException {

    public TemplateOverlapException(LocalTime first, LocalTime second, int durationMinutes) {
        super(
                "schedule.template_overlap",
                Map.of("first", first, "second", second, "durationMinutes", durationMinutes));
    }
}
