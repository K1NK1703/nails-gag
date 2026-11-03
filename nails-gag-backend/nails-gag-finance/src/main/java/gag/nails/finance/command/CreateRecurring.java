package gag.nails.finance.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новый шаблон повторяющейся ежемесячной записи; вид — вид категории. Пределы суммы и комментария — как у
 * {@link AddEntry}; пределы проверяет модуль.
 *
 * @param categoryId категория, не скрытая
 * @param dayOfMonth число месяца, 1–31; в коротком месяце — его последний день
 * @param startsOn первый день шаблона: не раньше {@code 2000-01-01}, не позже сегодняшнего плюс 366 дней; если он в
 *     прошлом, ближайший запуск создаст записи и за прошедшие месяцы
 * @param endsOn последний день шаблона, не раньше {@code startsOn}, в тех же пределах; пусто — без конца
 */
public record CreateRecurring(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @Nullable String comment,
        int dayOfMonth,
        @NotNull LocalDate startsOn,
        @Nullable LocalDate endsOn) {}
