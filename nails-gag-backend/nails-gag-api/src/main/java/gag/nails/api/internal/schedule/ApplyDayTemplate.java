package gag.nails.api.internal.schedule;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code POST /api/schedule/days/apply-template}: открыть дни по шаблону дня.
 *
 * @param templateId шаблон дня
 * @param dates дни в поясе салона; уже открытые пропускаются, повторы схлопываются
 */
record ApplyDayTemplate(@NotNull UUID templateId, @NotEmpty List<@NotNull LocalDate> dates) {}
