package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Период {@code from}/{@code to} в запросе (спецификация API, §4.1): включительно, не назад и не длиннее 93 дней. */
class PeriodsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    @Test
    void toBeforeFromIsRejected() {
        assertThatThrownBy(() -> Periods.check(FROM, FROM.minusDays(1)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("to"))
                .satisfies(e -> assertThat(((ValidationException) e).reason()).isEqualTo("must not be before from"));
        assertThatCode(() -> Periods.check(FROM, FROM)).doesNotThrowAnyException();
    }

    @Test
    void ninetyThreeDaysPass() {
        assertThatCode(() -> Periods.check(FROM, FROM.plusDays(Periods.MAX_DAYS - 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void ninetyFourDaysAreRejected() {
        assertThatThrownBy(() -> Periods.check(FROM, FROM.plusDays(Periods.MAX_DAYS)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("to"))
                .satisfies(e ->
                        assertThat(((ValidationException) e).reason()).isEqualTo("period must not exceed 93 days"));
    }
}
