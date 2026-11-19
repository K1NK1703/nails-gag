package gag.nails.booking.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Сумма предоплаты по умолчанию — процент от прайса, до рубля (половина — вверх), не меньше рубля. */
class BookingSettingsViewTest {

    @Test
    void defaultPrepaymentAmountIsPercentOfPlannedPriceInWholeRubles() {
        assertThat(settings(30).defaultPrepaymentAmount(new BigDecimal("2500.00")))
                .isEqualTo(new BigDecimal("750.00"));
        assertThat(settings(33).defaultPrepaymentAmount(new BigDecimal("2500.00")))
                .isEqualTo(new BigDecimal("825.00"));
        assertThat(settings(30).defaultPrepaymentAmount(new BigDecimal("1005.00")))
                .isEqualTo(new BigDecimal("302.00"));
        assertThat(settings(1).defaultPrepaymentAmount(new BigDecimal("10.00"))).isEqualTo(new BigDecimal("1.00"));
        assertThat(settings(1).defaultPrepaymentAmount(BigDecimal.ZERO)).isEqualTo(new BigDecimal("1.00"));
    }

    private static BookingSettingsView settings(int prepaymentPercent) {
        return new BookingSettingsView(3, 60, 24, 48, prepaymentPercent, 15, 7);
    }
}
