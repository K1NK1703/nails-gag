package gag.nails.api.internal.finance;

import gag.nails.finance.command.UpdateRecurring;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PATCH /api/finance/recurring/{id}}: все поля шаблона заново, команда {@link UpdateRecurring}; пределы —
 * как у {@link CreateRecurringEntry}. Категория — того же вида, что шаблон; скрытую категорию шаблона можно оставить,
 * но нельзя выбрать другую скрытую. Изменение действует только на будущие записи.
 *
 * @param comment комментарий записей до 500 символов; {@code null} или пустой — без комментария
 * @param dayOfMonth число месяца, 1–31
 * @param endsOn последний день шаблона; {@code null} — без конца
 */
record ChangeRecurringEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @Schema(maxLength = 500) @Nullable String comment,
        @Schema(minimum = "1", maximum = "31") int dayOfMonth,
        @NotNull LocalDate startsOn,
        @Nullable LocalDate endsOn) {

    UpdateRecurring toCommand() {
        return new UpdateRecurring(categoryId, amount, comment, dayOfMonth, startsOn, endsOn);
    }
}
