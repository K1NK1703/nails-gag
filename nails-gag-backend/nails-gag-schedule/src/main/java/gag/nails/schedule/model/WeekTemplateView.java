package gag.nails.schedule.model;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.UUID;

/**
 * Шаблон недели: рабочие дни недели и шаблон дня для каждого.
 *
 * @param days дни недели, в которые салон работает, → шаблон дня; дня нет в карте — выходной
 */
public record WeekTemplateView(UUID id, String name, Map<DayOfWeek, UUID> days) {}
