package gag.nails.schedule.internal.template;

import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.TemplateOverlapException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.TreeSet;

/** Времена начала шаблона дня: нормализация и проверка на пересечение при данной длительности слота. */
public final class TemplateTimes {

    private TemplateTimes() {}

    /** По возрастанию; повторы — ошибка команды. */
    static List<LocalTime> normalized(List<LocalTime> startTimes) {
        var sorted = new TreeSet<>(startTimes);
        if (sorted.size() != startTimes.size()) {
            throw new ValidationException("startTimes", "must not contain duplicates");
        }
        return List.copyOf(sorted);
    }

    /** Соседние времена отстоят друг от друга не меньше, чем на длительность слота. {@code sorted} — по возрастанию. */
    public static void requireNoOverlap(List<LocalTime> sorted, int durationMinutes) {
        for (var i = 1; i < sorted.size(); i++) {
            var previous = sorted.get(i - 1);
            var next = sorted.get(i);
            if (Duration.between(previous, next).toMinutes() < durationMinutes) {
                throw new TemplateOverlapException(previous, next, durationMinutes);
            }
        }
    }
}
