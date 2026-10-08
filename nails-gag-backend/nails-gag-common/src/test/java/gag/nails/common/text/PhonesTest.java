package gag.nails.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhonesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+79211234567",
                "79211234567",
                "89211234567",
                "9211234567",
                "8 (921) 123-45-67",
                "+7 921 123.45.67",
                "8-921-123-45-67"
            })
    void russianSpellingsNormalizeToE164(String raw) {
        assertThat(Phones.normalize(raw)).contains("+79211234567");
        assertThat(Phones.parse(raw)).contains("+79211234567");
    }

    @Test
    void foreignE164NumbersAreKept() {
        assertThat(Phones.normalize("+1 (415) 555-2671")).contains("+14155552671");
    }

    @Test
    void blankIsEmpty() {
        assertThat(Phones.normalize(null)).isEmpty();
        assertThat(Phones.normalize("   ")).isEmpty();
        assertThat(Phones.parse(null)).isEmpty();
        assertThat(Phones.parse("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a phone", "+7 *** *** ** 67", "12345", "+09211234567", "921123456"})
    void unreadableIsRejectedByNormalizeAndEmptyForParse(String raw) {
        assertThatThrownBy(() -> Phones.normalize(raw))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "phone"));
        assertThat(Phones.parse(raw)).isEmpty();
    }

    @Test
    void requireRejectsBlank() {
        assertThat(Phones.require("8 921 123 45 67")).isEqualTo("+79211234567");
        assertThatThrownBy(() -> Phones.require(" ")).isInstanceOf(ValidationException.class);
    }

    @Test
    void prefixStripsNoiseAndReplacesLeadingEight() {
        assertThat(Phones.prefix("8 921 98")).contains("+792198");
        assertThat(Phones.prefix("+7 (921)")).contains("+7921");
        assertThat(Phones.prefix("921")).contains("921");
        assertThat(Phones.prefix("Иван")).isEmpty();
        assertThat(Phones.prefix("921x")).isEmpty();
    }
}
