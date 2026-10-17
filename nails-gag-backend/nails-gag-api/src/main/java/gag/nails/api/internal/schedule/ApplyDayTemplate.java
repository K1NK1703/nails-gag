package gag.nails.api.internal.schedule;

import gag.nails.api.internal.http.PeriodParams;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code POST /api/schedule/days/apply-template}: открыть дни по шаблону дня.
 *
 * @param templateId шаблон дня
 * @param dates дни в поясе салона; уже открытые пропускаются, повторы схлопываются. Не больше
 *     {@value PeriodParams#MAX_DAYS} — как дней в периоде (спецификация API, §4.1): модуль числа дат не ограничивает,
 *     а один запрос не должен открывать расписание на годы вперёд
 */
record ApplyDayTemplate(
        @NotNull UUID templateId,
        @NotEmpty @Size(max = PeriodParams.MAX_DAYS) List<@NotNull LocalDate> dates) {}
