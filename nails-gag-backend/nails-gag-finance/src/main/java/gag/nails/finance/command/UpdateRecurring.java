package gag.nails.finance.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новые поля шаблона; пределы — как у {@link CreateRecurring}. Категория — того же вида, что шаблон; скрытую можно
 * оставить, но не выбрать заново. Изменение действует только на будущие записи.
 */
public record UpdateRecurring(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @Nullable String comment,
        int dayOfMonth,
        @NotNull LocalDate startsOn,
        @Nullable LocalDate endsOn) {}
