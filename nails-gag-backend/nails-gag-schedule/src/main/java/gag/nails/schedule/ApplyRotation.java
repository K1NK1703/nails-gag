package gag.nails.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * План применения ротации: период и шаблон недели для каждой недели периода — то, что вернул
 * предпросмотр, возможно с переставленными мастером шаблонами. Шаблоны должны входить в ротацию.
 */
public record ApplyRotation(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        @NotEmpty List<@Valid @NotNull WeekChoice> weeks) {}
