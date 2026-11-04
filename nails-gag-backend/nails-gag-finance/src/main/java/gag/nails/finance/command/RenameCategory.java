package gag.nails.finance.command;

import jakarta.validation.constraints.NotBlank;

/**
 * Новое название категории.
 *
 * @param name название; пробелы по краям отбрасываются, остальное — до 60 символов (длину проверяет модуль)
 */
public record RenameCategory(@NotBlank String name) {}
