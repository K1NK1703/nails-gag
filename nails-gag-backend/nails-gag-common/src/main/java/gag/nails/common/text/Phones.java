package gag.nails.common.text;

import gag.nails.common.error.ValidationException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Приводит телефон к E.164. Российские номера принимаются через 8, через 7, через +7 и как десять
 * цифр мобильного с девятки; пробелы, скобки, дефисы и точки между цифрами не мешают. Одна
 * нормализация на все модули: карточки клиентов и телефоны от провайдеров входа сравниваются по
 * одинаково записанному номеру.
 */
public final class Phones {

    private static final Pattern NOISE = Pattern.compile("[\\s ().\\-]");
    private static final Pattern RUSSIAN_WITHOUT_PLUS = Pattern.compile("[78]\\d{10}");
    private static final Pattern RUSSIAN_MOBILE_WITHOUT_CODE = Pattern.compile("9\\d{9}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");
    private static final Pattern DIGITS = Pattern.compile("\\+?\\d+");

    private Phones() {}

    /**
     * Нормализованный телефон; пусто, если телефон не указан.
     *
     * @throws ValidationException если указанное нельзя прочитать как телефон
     */
    public static Optional<String> normalize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(raw)
                .orElseThrow(() -> new ValidationException("phone", "must be a phone number like +79211234567")));
    }

    /** Как {@link #normalize}, но неразборчивый телефон — просто пусто: для поиска, а не для записи. */
    public static Optional<String> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        var digits = NOISE.matcher(raw).replaceAll("");
        if (RUSSIAN_WITHOUT_PLUS.matcher(digits).matches()) {
            digits = "+7" + digits.substring(1);
        } else if (RUSSIAN_MOBILE_WITHOUT_CODE.matcher(digits).matches()) {
            digits = "+7" + digits;
        }
        return E164.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
    }

    /**
     * Начало номера, набранное как телефон («8 921 98», «+7 (921)»): без мусора, восьмёрка в начале
     * заменена на {@code +7}. Пусто, если в строке есть что-то кроме цифр, плюса и мусора.
     */
    public static Optional<String> prefix(String raw) {
        var digits = NOISE.matcher(raw).replaceAll("");
        if (!DIGITS.matcher(digits).matches()) {
            return Optional.empty();
        }
        return Optional.of(digits.startsWith("8") ? "+7" + digits.substring(1) : digits);
    }

    /**
     * Нормализованный телефон, который обязан быть указан.
     *
     * @throws ValidationException если телефон пуст или его нельзя прочитать
     */
    public static String require(String raw) {
        return normalize(raw).orElseThrow(() -> new ValidationException("phone", "must not be blank"));
    }
}
