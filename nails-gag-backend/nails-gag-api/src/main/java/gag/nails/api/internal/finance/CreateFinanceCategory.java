package gag.nails.api.internal.finance;

import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.model.EntryKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Тело {@code POST /api/finance/categories}: поля команды {@link CreateCategory}. Длину названия проверяет модуль в
 * символах (эмодзи — один), здесь она только в схеме для фронта.
 *
 * @param kind вид: доход или расход
 * @param name название, 1–60 символов без пробелов по краям; уникально в виде без учёта регистра
 */
record CreateFinanceCategory(
        @NotNull EntryKind kind,
        @NotBlank @Schema(minLength = 1, maxLength = 60) String name) {

    CreateCategory toCommand() {
        return new CreateCategory(kind, name);
    }
}
