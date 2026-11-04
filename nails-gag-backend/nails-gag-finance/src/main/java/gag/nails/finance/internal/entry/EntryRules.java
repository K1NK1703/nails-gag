package gag.nails.finance.internal.entry;

import gag.nails.common.error.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jspecify.annotations.Nullable;

/**
 * Пределы суммы и комментария, общие для ручных записей и шаблонов повторяющихся записей: колонки {@code amount} и
 * {@code comment} у них одинаковые. Значения приводятся к виду, в котором их хранит база, поэтому равные по смыслу
 * поля равны и по {@code equals}.
 */
public final class EntryRules {

    /** Наибольшая сумма — предел {@code numeric(12,2)}. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** Знаков после запятой у суммы — как в {@code numeric(12,2)}. */
    private static final int AMOUNT_SCALE = 2;

    /** Предел длины комментария — как в {@code CHECK} колонок {@code comment}. */
    private static final int MAX_COMMENT_LENGTH = 500;

    private EntryRules() {}

    /**
     * Сумма больше нуля, не больше {@link #MAX_AMOUNT}, не больше двух значащих знаков после запятой — с ровно двумя
     * знаками, как её вернёт база: иначе {@code 1500} и {@code 1500.00} были бы разными суммами для журнала.
     *
     * @throws ValidationException (поле {@code amount}) если сумма вне пределов
     */
    public static BigDecimal amount(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new ValidationException("amount", "must be greater than 0");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException("amount", "must be at most " + MAX_AMOUNT);
        }
        if (amount.stripTrailingZeros().scale() > AMOUNT_SCALE) {
            throw new ValidationException("amount", "must have at most " + AMOUNT_SCALE + " decimal places");
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Комментарий без пробелов по краям (как название категории); пустой — его отсутствие. Длина — в символах
     * (кодовых точках), как {@code char_length} в {@code CHECK} базы: эмодзи — один символ, хотя в Java это две
     * единицы UTF-16.
     *
     * @throws ValidationException (поле {@code comment}) если комментарий длиннее 500 символов
     */
    public static @Nullable String comment(@Nullable String comment) {
        if (comment == null) {
            return null;
        }
        var stripped = comment.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        return stripped;
    }
}
