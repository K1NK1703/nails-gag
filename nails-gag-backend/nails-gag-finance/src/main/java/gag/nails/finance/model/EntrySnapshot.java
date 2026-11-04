package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Поля записи в журнале изменений на момент изменения.
 *
 * @param categoryName название категории на момент изменения: переименование категории снимок не меняет
 */
public record EntrySnapshot(
        EntryKind kind,
        UUID categoryId,
        String categoryName,
        BigDecimal amount,
        LocalDate occurredOn,
        @Nullable String comment,
        @Nullable UUID recurringId) {}
