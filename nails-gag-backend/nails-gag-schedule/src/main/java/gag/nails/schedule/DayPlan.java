package gag.nails.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * День в предпросмотре ротации.
 *
 * @param dayTemplateId шаблон дня по шаблону недели; {@code null} для выходного
 * @param startTimes времена начала слотов по шаблону дня (и для {@link DayPlanStatus#ALREADY_OPEN},
 *     чтобы мастер видел, что не будет создано); пусто для выходного
 */
public record DayPlan(
        LocalDate date, DayPlanStatus status, @Nullable UUID dayTemplateId, List<LocalTime> startTimes) {}
