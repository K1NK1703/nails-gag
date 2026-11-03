package gag.nails.finance.model;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Отбор журнала операций: период по дню операции (обе границы включительно) и, если заданы, виды операций и
 * категория.
 *
 * @param types только операции этих видов; пусто или не задано — все виды
 * @param categoryId только ручные записи этой категории: у визитов и удержанных предоплат категории нет, с этим
 *     отбором их в журнале нет; пусто — без отбора по категории
 */
public record JournalFilter(
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        @Nullable Set<@NotNull JournalType> types,
        @Nullable UUID categoryId) {}
