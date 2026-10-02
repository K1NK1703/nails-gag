package gag.nails.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.Map;
import java.util.UUID;

/**
 * Создание шаблона недели.
 *
 * @param days рабочие дни недели → шаблон дня; дня нет в карте — выходной; пустая карта допустима
 */
public record CreateWeekTemplate(
        @NotBlank @Size(max = 100) String name, @NotNull Map<DayOfWeek, @NotNull UUID> days) {}
