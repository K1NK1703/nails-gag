package gag.nails.finance;

import gag.nails.finance.command.CreateRecurring;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.model.RecurringView;
import gag.nails.testsupport.TestTimeConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

/** Шаблоны повторяющихся записей и перевод часов на нужный день салона. */
abstract class RecurringTestSupport extends FinanceTestSupport {

    private static final ZoneId SALON = ZoneId.of("Europe/Moscow");

    @Autowired
    FinanceRecurring recurring;

    @Autowired
    FinanceMaintenance maintenance;

    RecurringView template(
            UUID categoryId, String amount, int dayOfMonth, LocalDate startsOn, @Nullable LocalDate endsOn) {
        return recurring.create(
                new CreateRecurring(categoryId, new BigDecimal(amount), null, dayOfMonth, startsOn, endsOn), MASTER);
    }

    /** Те же поля шаблона, кроме заданных. */
    static UpdateRecurring change(RecurringView template, String amount, int dayOfMonth, LocalDate startsOn) {
        return new UpdateRecurring(
                template.categoryId(),
                new BigDecimal(amount),
                template.comment(),
                dayOfMonth,
                startsOn,
                template.endsOn());
    }

    /** Часы — на этот день салона, в то же время суток, что и {@link TestTimeConfiguration#START} (09:00). */
    void today(LocalDate day) {
        clock.set(day.atTime(LocalTime.of(9, 0)).atZone(SALON).toInstant());
    }
}
