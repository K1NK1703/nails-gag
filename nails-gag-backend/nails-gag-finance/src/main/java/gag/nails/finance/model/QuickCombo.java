package gag.nails.finance.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Недавняя пара «категория + сумма» для быстрого ввода. */
public record QuickCombo(UUID categoryId, String categoryName, BigDecimal amount) {}
