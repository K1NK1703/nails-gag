package gag.nails.api.internal.finance;

import gag.nails.finance.command.CreateRecurring;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/finance/recurring}: поля команды {@link CreateRecurring} — шаблон ежемесячной записи. Пределы
 * суммы и комментария — как у {@link AddFinanceEntry}; все пределы проверяет модуль, здесь они только в схеме.
 *
 * @param categoryId категория, не скрытая; вид шаблона — вид категории
 * @param comment комментарий записей до 500 символов; {@code null} или пустой — без комментария
 * @param dayOfMonth число месяца, 1–31; в коротком месяце — его последний день
 * @param startsOn первый день шаблона: не раньше {@code 2000-01-01}, не позже сегодняшнего плюс 366 дней; в прошлом —
 *     записи за наступившие дни создаются сразу
 * @param endsOn последний день шаблона, не раньше {@code startsOn}, в тех же пределах; {@code null} — без конца
 */
record CreateRecurringEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @Schema(maxLength = 500) @Nullable String comment,
        @Schema(minimum = "1", maximum = "31") int dayOfMonth,
        @NotNull LocalDate startsOn,
        @Nullable LocalDate endsOn) {

    CreateRecurring toCommand() {
        return new CreateRecurring(categoryId, amount, comment, dayOfMonth, startsOn, endsOn);
    }
}
