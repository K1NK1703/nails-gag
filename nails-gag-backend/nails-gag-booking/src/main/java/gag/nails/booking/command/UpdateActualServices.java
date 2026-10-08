package gag.nails.booking.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Фактический состав выполненного визита и итоговая сумма. Названия и цены услуг берутся из
 * каталога на момент правки; сумма задаётся явно — мастер мог сделать скидку.
 */
public record UpdateActualServices(
        @NotNull UUID mainServiceId,
        @NotNull List<@NotNull UUID> addonServiceIds,

        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        BigDecimal finalAmount) {}
