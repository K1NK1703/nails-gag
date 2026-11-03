package gag.nails.finance.internal.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Изменяемые поля шаблона после проверок, в виде, в котором их хранит база. */
record RecurringFields(
        UUID categoryId,
        BigDecimal amount,
        @Nullable String comment,
        int dayOfMonth,
        LocalDate startsOn,
        @Nullable LocalDate endsOn) {}
