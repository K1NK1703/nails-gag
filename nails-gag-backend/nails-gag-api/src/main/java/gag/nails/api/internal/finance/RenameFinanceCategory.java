package gag.nails.api.internal.finance;

import gag.nails.finance.command.RenameCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Тело {@code PATCH /api/finance/categories/{id}}: новое название, правила — как у {@link CreateFinanceCategory}.
 *
 * @param name название, 1–60 символов без пробелов по краям
 */
record RenameFinanceCategory(
        @NotBlank @Schema(minLength = 1, maxLength = 60) String name) {

    RenameCategory toCommand() {
        return new RenameCategory(name);
    }
}
