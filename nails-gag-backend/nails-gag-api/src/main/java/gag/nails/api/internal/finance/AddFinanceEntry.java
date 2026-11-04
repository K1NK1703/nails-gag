package gag.nails.api.internal.finance;

import gag.nails.finance.command.AddEntry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/finance/entries}: поля команды {@link AddEntry}; вид записи — вид категории. Пределы суммы, дня
 * и длину комментария проверяет модуль, длина — в символах; здесь она только в схеме для фронта.
 *
 * @param categoryId категория, не скрытая
 * @param amount сумма в рублях: больше нуля, не больше {@code 9999999999.99}, не больше двух знаков после запятой
 * @param occurredOn день операции: не раньше {@code 2000-01-01}, не позже сегодняшнего плюс 366 дней
 * @param comment комментарий до 500 символов без пробелов по краям; {@code null} или пустой — без комментария
 */
record AddFinanceEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @NotNull LocalDate occurredOn,
        @Schema(maxLength = 500) @Nullable String comment) {

    AddEntry toCommand() {
        return new AddEntry(categoryId, amount, occurredOn, comment);
    }
}
