package gag.nails.api.internal.finance;

import gag.nails.finance.model.EntryKind;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code PUT /api/finance/categories/order}: новый порядок категорий одного вида. Полноту списка проверяет модуль.
 *
 * @param kind вид, категории которого переставляются
 * @param orderedIds все категории вида, и скрытые, каждая ровно один раз; первая — первой в списке
 */
record ReorderFinanceCategories(
        @NotNull EntryKind kind, @NotNull List<@NotNull UUID> orderedIds) {}
