package gag.nails.finance.internal.recurring;

import gag.nails.finance.internal.category.CategoryLookup;
import gag.nails.finance.internal.entry.TemplateEntries;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Расписание шаблона: какие записи создать к сегодняшнему дню и куда ставить прогресс. Только в транзакции, где
 * строка шаблона уже под блокировкой ({@code MANDATORY}): записи, их журнал и прогресс фиксируются вместе.
 *
 * <p>Название категории для журнала читается без блокировки категории: в одной транзакции шаблона блокируется не
 * больше одной категории (правка — новую, разделяемо), иначе две блокировки категорий могли бы встать накрест с
 * перестановкой категорий.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class RecurringSchedule {

    private final TemplateEntries entries;
    private final CategoryLookup categories;
    private final Clock clock;

    RecurringSchedule(TemplateEntries entries, CategoryLookup categories, Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.clock = clock;
    }

    /**
     * Создаёт записи шаблона за дни после прогресса по {@code today} включительно — с {@code startsOn}, по
     * {@code endsOn}, если шаблон не на паузе, — и сдвигает прогресс до {@code today} (и на паузе: пропущенное на
     * паузе не догоняется).
     *
     * @return сколько записей создано
     */
    int createDue(RecurringEntity recurring, LocalDate today) {
        var created = 0;
        if (!recurring.paused()) {
            var through = recurring.generatedThrough();
            var from = through == null || through.isBefore(recurring.startsOn())
                    ? recurring.startsOn()
                    : through.plusDays(1);
            var endsOn = recurring.endsOn();
            var to = endsOn == null || today.isBefore(endsOn) ? today : endsOn;
            String categoryName = null;
            for (var month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
                var day = recurring.dueIn(month);
                if (day.isBefore(from) || day.isAfter(to)) {
                    continue;
                }
                if (categoryName == null) {
                    categoryName = categories.name(recurring.categoryId());
                }
                var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                if (entries.create(
                        recurring.id(),
                        recurring.kind(),
                        recurring.categoryId(),
                        categoryName,
                        recurring.amount(),
                        recurring.comment(),
                        day,
                        now)) {
                    created++;
                }
            }
        }
        recurring.advanceGeneratedThrough(today);
        return created;
    }

    /**
     * Создана ли (или пропущена на паузе) запись текущего месяца по нынешнему числу шаблона: её день в этом месяце
     * уже пройден прогрессом и лежит в сроке шаблона. Вызывать после {@link #createDue} и до смены полей.
     */
    boolean thisMonthDone(RecurringEntity recurring, LocalDate today) {
        var due = recurring.dueIn(YearMonth.from(today));
        var through = recurring.generatedThrough();
        var endsOn = recurring.endsOn();
        return through != null
                && !due.isAfter(through)
                && !due.isBefore(recurring.startsOn())
                && (endsOn == null || !due.isAfter(endsOn));
    }

    /**
     * Прогресс после смены числа месяца — не больше одной записи за месяц и без пропуска месяца: если запись
     * текущего месяца уже есть ({@code thisMonthDone}), прогресс — конец месяца, следующая запись — в новое число
     * следующего; если ещё нет — день перед новым числом в текущем месяце (если новое число уже прошло, ближайший
     * запуск создаст запись этим числом). Прогресс может уйти назад, но не дальше начала текущего месяца, а в этом
     * месяце у шаблона только один день — новое число.
     */
    void afterDayChange(RecurringEntity recurring, boolean thisMonthDone, LocalDate today) {
        var month = YearMonth.from(today);
        recurring.setGeneratedThrough(
                thisMonthDone ? month.atEndOfMonth() : recurring.dueIn(month).minusDays(1));
    }
}
