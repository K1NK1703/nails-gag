package gag.nails.clients.internal;

import gag.nails.common.error.ValidationException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Приводит телефон к E.164. Российские номера принимаются и через 8, и через 7, и через +7. */
final class Phones {

    private static final Pattern NOISE = Pattern.compile("[\\s()\\-]");
    private static final Pattern RUSSIAN_WITHOUT_PLUS = Pattern.compile("[78]\\d{10}");
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    private Phones() {}

    static Optional<String> normalize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        var digits = NOISE.matcher(raw).replaceAll("");
        if (RUSSIAN_WITHOUT_PLUS.matcher(digits).matches()) {
            digits = "+7" + digits.substring(1);
        }
        if (!E164.matcher(digits).matches()) {
            throw new ValidationException("phone", "must be a phone number like +79211234567");
        }
        return Optional.of(digits);
    }

    static String require(String raw) {
        return normalize(raw).orElseThrow(() -> new ValidationException("phone", "must not be blank"));
    }
}
