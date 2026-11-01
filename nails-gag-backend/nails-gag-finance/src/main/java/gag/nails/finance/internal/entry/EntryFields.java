package gag.nails.finance.internal.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Изменяемые поля записи после проверок: сумма — ровно с двумя знаками после запятой, как {@code numeric(12,2)},
 * пустой комментарий — {@code null}. Поэтому равные по смыслу поля равны и по {@code equals}.
 */
record EntryFields(
        UUID categoryId,
        BigDecimal amount,
        LocalDate occurredOn,
        @Nullable String comment) {}
