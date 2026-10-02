package gag.nails.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;

/** Правка шаблона дня: имя и полный новый набор времён начала. Уже созданные слоты не меняются. */
public record UpdateDayTemplate(
        @NotBlank @Size(max = 100) String name, @NotEmpty List<@NotNull LocalTime> startTimes) {}
