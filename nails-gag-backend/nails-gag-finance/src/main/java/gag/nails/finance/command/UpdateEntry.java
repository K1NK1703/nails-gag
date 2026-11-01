package gag.nails.finance.command;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новые поля ручной записи; пределы — как у {@link AddEntry}. Категория — того же вида, что запись; скрытую можно
 * оставить, но не выбрать заново.
 *
 * @param comment комментарий до 500 символов; пустой — комментария нет
 */
public record UpdateEntry(
        @NotNull UUID categoryId,
        @NotNull BigDecimal amount,
        @NotNull LocalDate occurredOn,
        @Nullable String comment) {}
