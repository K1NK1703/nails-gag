package gag.nails.schedule.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.UUID;

/** Правка шаблона недели: имя и полная новая карта рабочих дней. */
public record UpdateWeekTemplate(
        @NotBlank @Size(max = 100) String name, @NotNull Map<@NotNull DayOfWeek, @NotNull UUID> days) {}
