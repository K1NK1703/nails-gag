package gag.nails.api.internal.catalog;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code PUT /api/services/order}: новый порядок прайса. Повторы и неизвестные услуги проверяет модуль.
 *
 * @param orderedServiceIds услуги в новом порядке; не перечисленные следуют за ними в прежнем порядке
 */
record ReorderServices(@NotNull List<@NotNull UUID> orderedServiceIds) {}
