package gag.nails.finance.command;

import gag.nails.finance.model.EntryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Новая категория; встаёт в конец списка своего вида.
 *
 * @param name название; пробелы по краям отбрасываются, остальное — до 60 символов (длину проверяет модуль)
 */
public record CreateCategory(
        @NotNull EntryKind kind, @NotBlank String name) {}
