package gag.nails.schedule.command;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Настройка ротации. {@code anchorMonday} — понедельник; {@code anchorIndex} — индекс шаблона из
 * {@code weekTemplateIds}, который приходится на эту неделю. Шаблон недели может повторяться в
 * списке.
 */
public record SetRotation(
        @NotEmpty List<@NotNull UUID> weekTemplateIds,
        @NotNull LocalDate anchorMonday,
        @Min(0) int anchorIndex) {}
