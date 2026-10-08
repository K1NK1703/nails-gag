package gag.nails.schedule.model;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Шаблон дня: времена начала слотов по локальному времени салона, по возрастанию.
 *
 * @param isDefault шаблон по умолчанию — не больше одного на весь список
 */
public record DayTemplateView(UUID id, String name, boolean isDefault, int sortOrder, List<LocalTime> startTimes) {}
