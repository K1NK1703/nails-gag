package gag.nails.booking.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Фактический состав выполненного визита, итоговая сумма и причина суммы. Итог может быть меньше прайса (скидка,
 * бесплатно) и больше него; скидку считает отчёт. Названия и цены услуг берутся из каталога на момент правки.
 *
 * @param amountNote причина итоговой суммы («подарок», «переделка»), до 200 символов — длину проверяет модуль;
 *     пустая — причины нет
 */
public record UpdateVisit(
        @NotNull UUID mainServiceId,
        @NotNull List<@NotNull UUID> addonServiceIds,

        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        BigDecimal finalAmount,

        @Nullable String amountNote) {}
