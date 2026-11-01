package gag.nails.finance.model;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Отбор ручных записей: период по дню операции (обе границы включительно) и, если заданы, вид и категория.
 *
 * @param kind только этого вида; пусто — оба
 * @param categoryId только этой категории; пусто — любой
 */
public record EntryFilter(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        @Nullable EntryKind kind,
        @Nullable UUID categoryId) {}
