package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Ручная запись для журнала операций ({@link EntryReports#lines}); название категории — текущее. */
public record EntryLine(
        UUID entryId, EntryKind kind, UUID categoryId, String categoryName, BigDecimal amount, LocalDate occurredOn) {}
