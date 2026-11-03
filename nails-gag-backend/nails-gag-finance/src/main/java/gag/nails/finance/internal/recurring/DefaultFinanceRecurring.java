package gag.nails.finance.internal.recurring;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.ReportPeriod;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.common.validation.CommandValidator;
import gag.nails.finance.FinanceRecurring;
import gag.nails.finance.command.CreateRecurring;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.error.RecurringNotFoundException;
import gag.nails.finance.internal.category.CategoryLookup;
import gag.nails.finance.internal.category.CategoryRef;
import gag.nails.finance.internal.entry.EntryRules;
import gag.nails.finance.model.RecurringView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Изменения шаблона берут его строку под блокировку ({@link RecurringRepository#findLockedById}) — так же, как ночная
 * задача, — затем разделяемо строку новой категории ({@link CategoryLookup#forEntry}); записи шаблона вставляются
 * после. Правка сначала берёт блокировку списка категорий вида ({@link CategoryLookup#lockCategoriesOf}): она может
 * держать две категории (прежнюю и новую), и без этого встала бы накрест с перестановкой категорий. Порядок «список
 * вида → шаблон → запись → категория» — общий порядок модуля (см. {@code CategoryLocks}).
 *
 * <p>Создание и правка сразу создают записи за наступившие дни ({@link RecurringSchedule#createDue}), не дожидаясь
 * ночной задачи.
 */
@Service
@Transactional
class DefaultFinanceRecurring implements FinanceRecurring {

    private final RecurringRepository recurring;
    private final RecurringSchedule schedule;
    private final CategoryLookup categories;
    private final CommandValidator validator;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultFinanceRecurring(
            RecurringRepository recurring,
            RecurringSchedule schedule,
            CategoryLookup categories,
            CommandValidator validator,
            SalonTimeZone salonTimeZone,
            Clock clock) {
        this.recurring = recurring;
        this.schedule = schedule;
        this.categories = categories;
        this.validator = validator;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecurringView> list(Actor actor) {
        actor.requireRead(Section.FINANCE);
        return recurring.findAllRows().stream().map(RecurringRow::toView).toList();
    }

    @Override
    public RecurringView create(CreateRecurring command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var fields = fields(
                command.categoryId(),
                command.amount(),
                command.comment(),
                command.dayOfMonth(),
                command.startsOn(),
                command.endsOn());
        var category = categories.forEntry(command.categoryId());
        requireVisible(category);
        var created = recurring.save(new RecurringEntity(category.kind(), fields, now()));
        schedule.createDue(created, today());
        return created.toView(category.name());
    }

    @Override
    public RecurringView update(UUID recurringId, UpdateRecurring command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var fields = fields(
                command.categoryId(),
                command.amount(),
                command.comment(),
                command.dayOfMonth(),
                command.startsOn(),
                command.endsOn());
        // Вид шаблона не меняется: по нему блокировка списка берётся до строки шаблона, в порядке модуля. Берётся
        // всегда, а не только при смене категории: категорию шаблона могли сменить, пока правка ждала его строку.
        var kind = recurring.findKindById(recurringId).orElseThrow(() -> new RecurringNotFoundException(recurringId));
        categories.lockCategoriesOf(kind);
        var template = requireLocked(recurringId);
        var today = today();
        // Наступившие дни — по прежним полям: изменение действует только на будущие записи.
        schedule.createDue(template, today);
        var category = categories.forEntry(command.categoryId());
        if (category.kind() != template.kind()) {
            throw new ValidationException("categoryId", "must be a category of the template kind " + template.kind());
        }
        // Скрытую категорию шаблона можно оставить, но не выбрать заново.
        if (!category.id().equals(template.categoryId())) {
            requireVisible(category);
        }
        var dayChanged = fields.dayOfMonth() != template.dayOfMonth();
        var thisMonthDone = schedule.thisMonthDone(template, today);
        template.apply(fields, now());
        if (dayChanged) {
            schedule.afterDayChange(template, thisMonthDone, today);
        }
        // Наступившие дни по новым полям — сразу: например, новое число месяца уже прошло, а записи месяца нет.
        schedule.createDue(template, today);
        return template.toView(category.name());
    }

    @Override
    public void pause(UUID recurringId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        var template = requireLocked(recurringId);
        if (template.paused()) {
            return;
        }
        // Дни до паузы, включая сегодняшний, — ещё не на паузе.
        schedule.createDue(template, today());
        template.setPaused(true, now());
    }

    @Override
    public void resume(UUID recurringId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        var template = requireLocked(recurringId);
        if (!template.paused()) {
            return;
        }
        // Дни паузы по вчерашний не догоняются; сегодняшний создастся, если задача ещё не прошла его на паузе.
        template.advanceGeneratedThrough(today().minusDays(1));
        template.setPaused(false, now());
    }

    @Override
    public void delete(UUID recurringId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        recurring.delete(requireLocked(recurringId));
    }

    private RecurringEntity requireLocked(UUID recurringId) {
        return recurring.findLockedById(recurringId).orElseThrow(() -> new RecurringNotFoundException(recurringId));
    }

    private static void requireVisible(CategoryRef category) {
        if (category.hidden()) {
            throw new ValidationException("categoryId", "category is hidden");
        }
    }

    /** Поля после проверок пределов, в виде, в котором их хранит база. */
    private RecurringFields fields(
            UUID categoryId,
            BigDecimal amount,
            @Nullable String comment,
            int dayOfMonth,
            LocalDate startsOn,
            @Nullable LocalDate endsOn) {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new ValidationException("dayOfMonth", "must be between 1 and 31");
        }
        var today = today();
        ReportPeriod.requireDay("startsOn", startsOn, today);
        if (endsOn != null) {
            ReportPeriod.requireDay("endsOn", endsOn, today);
            if (endsOn.isBefore(startsOn)) {
                throw new ValidationException("endsOn", "must not be before startsOn");
            }
        }
        return new RecurringFields(
                categoryId, EntryRules.amount(amount), EntryRules.comment(comment), dayOfMonth, startsOn, endsOn);
    }

    private LocalDate today() {
        return salonTimeZone.today(clock);
    }

    /** Время с точностью {@code timestamptz} (микросекунды): что записано, то и прочитается. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
