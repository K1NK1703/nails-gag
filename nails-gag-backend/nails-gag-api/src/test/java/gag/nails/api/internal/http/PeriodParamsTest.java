package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Период {@code from}/{@code to} в запросе (спецификация API, §4.1): включительно, не назад и не длиннее 93 дней. */
class PeriodParamsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);

    @Test
    void toBeforeFromIsRejected() {
        assertThatThrownBy(() -> PeriodParams.check(FROM, FROM.minusDays(1)))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.field()).isEqualTo("to");
                    assertThat(e.reason()).isEqualTo("must not be before from");
                });
    }

    @Test
    void oneDayPasses() {
        assertThatCode(() -> PeriodParams.check(FROM, FROM)).doesNotThrowAnyException();
    }

    @Test
    void ninetyThreeDaysPass() {
        assertThatCode(() -> PeriodParams.check(FROM, FROM.plusDays(PeriodParams.MAX_DAYS - 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void ninetyFourDaysAreRejected() {
        assertThatThrownBy(() -> PeriodParams.check(FROM, FROM.plusDays(PeriodParams.MAX_DAYS)))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.field()).isEqualTo("to");
                    assertThat(e.reason()).isEqualTo("period must not exceed 93 days");
                });
    }
}
