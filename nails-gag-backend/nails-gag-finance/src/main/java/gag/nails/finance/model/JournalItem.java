package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Операция журнала: визит, удержанная предоплата или ручная запись. Клиента не содержит.
 *
 * @param day день операции в поясе салона: день визита, день отмены или неявки, день ручной записи
 * @param amount итоговая сумма визита, сумма предоплаты или записи; у бесплатного визита — ноль
 * @param title «Визит: &lt;основная услуга&gt;», «Удержанная предоплата» или текущее название категории записи
 * @param categoryId категория ручной записи; у визита и предоплаты пусто
 * @param bookingId запись визита или предоплаты; у ручной записи пусто
 * @param entryId ручная запись; у визита и предоплаты пусто
 */
public record JournalItem(
        JournalType type,
        LocalDate day,
        BigDecimal amount,
        String title,
        @Nullable UUID categoryId,
        @Nullable UUID bookingId,
        @Nullable UUID entryId) {}
