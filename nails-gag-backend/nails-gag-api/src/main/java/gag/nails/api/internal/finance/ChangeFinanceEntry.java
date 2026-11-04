package gag.nails.api.internal.finance;

import gag.nails.finance.command.UpdateEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PATCH /api/finance/entries/{id}}: все поля записи заново, команда {@link UpdateEntry}; пределы — как у
 * {@link AddFinanceEntry}. Вид записи не меняется: категория — того же вида; скрытую категорию записи можно оставить,
 * но нельзя выбрать другую скрытую.
 *
 * @param comment комментарий до 500 символов без пробелов по краям; {@code null} или пустой — без комментария
 */
record ChangeFinanceEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @NotNull LocalDate occurredOn,
        @Schema(maxLength = 500) @Nullable String comment) {

    UpdateEntry toCommand() {
        return new UpdateEntry(categoryId, amount, occurredOn, comment);
    }
}
