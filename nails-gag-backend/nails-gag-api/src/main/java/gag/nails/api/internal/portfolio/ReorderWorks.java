package gag.nails.api.internal.portfolio;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code PUT /api/portfolio/order}: новый порядок работ на лендинге. Полноту списка проверяет модуль.
 *
 * @param orderedWorkIds все работы, каждая ровно один раз; первая — первой на лендинге
 */
record ReorderWorks(@NotNull List<@NotNull UUID> orderedWorkIds) {}
