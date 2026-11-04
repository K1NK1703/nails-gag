package gag.nails.analytics.internal.visitor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.analytics.AnalyticsModuleTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** Соль и отпечатки — только внутри транзакции приёма. */
@AnalyticsModuleTest
class VisitorsTransactionTest {

    private static final LocalDate DAY = LocalDate.of(2313, 3, 1);

    @Autowired
    Visitors visitors;

    @Autowired
    DaySalts salts;

    @Test
    void visitorsNeedCallerTransaction() {
        var visitor = Visitor.of("203.0.113.7", "Mozilla/5.0").orElseThrow();
        assertThatThrownBy(() -> visitors.firstVisitOfDay(DAY, visitor))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void saltsNeedCallerTransaction() {
        assertThatThrownBy(() -> salts.of(DAY)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
