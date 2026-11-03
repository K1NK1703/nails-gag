package gag.nails.finance.internal.entry;

import gag.nails.finance.model.CategoryTotal;
import gag.nails.finance.model.EntryKind;
import java.math.BigDecimal;
import java.util.UUID;

/** Сумма записей категории за период ({@link EntryReports#sumByCategory}). */
public record CategorySum(UUID categoryId, EntryKind kind, String name, boolean hidden, BigDecimal amount) {

    public CategoryTotal toTotal() {
        return new CategoryTotal(categoryId, name, hidden, amount);
    }
}
