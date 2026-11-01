package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Ручной доход или расход.
 *
 * @param kind вид — всегда вид категории
 * @param categoryName текущее название категории
 * @param amount сумма в рублях, больше нуля, с двумя знаками после запятой
 * @param occurredOn день операции
 * @param recurringId шаблон, которым создана запись; пусто — запись внесена вручную
 * @param updatedAt момент последнего фактического изменения; у неизменённой — момент создания
 */
public record EntryView(
        UUID id,
        EntryKind kind,
        UUID categoryId,
        String categoryName,
        BigDecimal amount,
        LocalDate occurredOn,
        @Nullable String comment,
        @Nullable UUID recurringId,
        Instant createdAt,
        Instant updatedAt) {}
