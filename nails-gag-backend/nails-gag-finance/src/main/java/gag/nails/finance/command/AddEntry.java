package gag.nails.finance.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новый ручной доход или расход; вид записи — вид категории. Пределы суммы, дня и комментария проверяет модуль.
 *
 * @param categoryId категория, не скрытая
 * @param amount сумма в рублях: больше нуля, не больше {@code 9 999 999 999.99}, не больше двух знаков после запятой
 * @param occurredOn день операции: не раньше {@code 2000-01-01}, не позже сегодняшнего плюс 366 дней
 * @param comment комментарий до 500 символов; пустой — комментария нет
 */
public record AddEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @NotNull LocalDate occurredOn,
        @Nullable String comment) {}
