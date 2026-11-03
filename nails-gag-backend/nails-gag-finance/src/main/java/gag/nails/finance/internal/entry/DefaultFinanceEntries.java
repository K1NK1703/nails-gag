package gag.nails.finance.internal.entry;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.common.time.ReportPeriod;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.common.validation.CommandValidator;
import gag.nails.finance.FinanceEntries;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.error.EntryNotFoundException;
import gag.nails.finance.internal.category.CategoryLookup;
import gag.nails.finance.internal.category.CategoryRef;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.finance.model.QuickCombo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Правка и удаление берут строку записи под блокировку ({@link EntryRepository#findLockedById}), затем —
 * разделяемо — строку категории ({@link CategoryLookup#forEntry}): «было» в журнале — состояние, которое никто не
 * меняет одновременно, а категорию не скроют и не переименуют, пока запись на неё ссылается и пишет её название в
 * журнал.
 */
@Service
@Transactional
class DefaultFinanceEntries implements FinanceEntries {

    /** Один день — не больше одной записи шаблона. */
    private static final String RECURRING_DAY_KEY = "entry_recurring_id_occurred_on_key";

    private static final String TEMPLATE_DAY_TAKEN =
            "an entry of the same recurring template already exists on that day";

    /** Сколько пар предлагает быстрый ввод. */
    private static final int RECENT_COMBOS = 5;

    private final EntryRepository entries;
    private final EntryJournal journal;
    private final CategoryLookup categories;
    private final CommandValidator validator;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultFinanceEntries(
            EntryRepository entries,
            EntryJournal journal,
            CategoryLookup categories,
            CommandValidator validator,
            SalonTimeZone salonTimeZone,
            Clock clock) {
        this.entries = entries;
        this.journal = journal;
        this.categories = categories;
        this.validator = validator;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public EntryView add(AddEntry command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var fields = fields(command.categoryId(), command.amount(), command.occurredOn(), command.comment());
        var category = categories.forEntry(command.categoryId());
        requireVisible(category);
        var now = now();
        var entry = entries.save(new EntryEntity(category.kind(), fields, now));
        journal.created(entry.id(), entry.snapshot(category.name()), actor.accountId(), now);
        return entry.toView(category.name());
    }

    @Override
    public EntryView update(UUID entryId, UpdateEntry command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var fields = fields(command.categoryId(), command.amount(), command.occurredOn(), command.comment());
        var entry = requireLocked(entryId);
        var category = categories.forEntry(command.categoryId());
        if (category.kind() != entry.kind()) {
            throw new ValidationException("categoryId", "must be a category of the entry kind " + entry.kind());
        }
        var sameCategory = category.id().equals(entry.categoryId());
        // Скрытую категорию записи можно оставить, но не выбрать заново.
        if (!sameCategory) {
            requireVisible(category);
        }
        // Прежняя категория — без блокировки: вторая блокировка категории могла бы встать накрест с перестановкой.
        var before = entry.snapshot(sameCategory ? category.name() : categories.name(entry.categoryId()));
        var after = entry.snapshotWith(fields, category.name());
        if (after.equals(before)) {
            return entry.toView(category.name());
        }
        requireTemplateDayFree(entryId, before.recurringId(), fields.occurredOn());
        var now = now();
        entry.apply(fields, now);
        flushMoved(entry);
        journal.updated(entryId, before, after, actor.accountId(), now);
        return entry.toView(category.name());
    }

    @Override
    public void delete(UUID entryId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        var entry = requireLocked(entryId);
        var category = categories.forEntry(entry.categoryId());
        journal.deleted(entryId, entry.snapshot(category.name()), actor.accountId(), now());
        entries.delete(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public EntryView get(UUID entryId, Actor actor) {
        actor.requireRead(Section.FINANCE);
        return entries.findRowById(entryId)
                .map(EntryRow::toView)
                .orElseThrow(() -> new EntryNotFoundException(entryId));
    }

    /**
     * Страница и {@code total} — два запроса. Вне транзакции своя с {@code REPEATABLE READ} даёт им один снимок базы.
     * Внутри чужой транзакции действует её уровень изоляции (Spring не меняет его у уже начатой).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Paged<EntryView> list(EntryFilter filter, Paging paging, Actor actor) {
        actor.requireRead(Section.FINANCE);
        validator.requireValid(filter);
        var period = ReportPeriod.of(filter.from(), filter.to(), today());
        var page = entries.findPage(
                period.from(),
                period.to(),
                filter.kind(),
                filter.categoryId(),
                PageRequest.of(paging.page(), paging.size()));
        return Paged.of(page.getContent(), paging, page.getTotalElements()).map(EntryRow::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuickCombo> recentCombos(EntryKind kind, Actor actor) {
        actor.requireRead(Section.FINANCE);
        // Вызывающий код на Java может передать null мимо проверок HTTP-слоя: ошибка запроса, а не пустой список.
        if (kind == null) {
            throw new ValidationException("kind", "must not be null");
        }
        return entries.findRecentCombos(kind, PageRequest.of(0, RECENT_COMBOS));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntryChangeView> history(UUID entryId, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var history = journal.history(entryId);
        // Пустой журнал у существующей записи — не «нет записи»: так бывает только у строки, вставленной в обход
        // модуля.
        if (history.isEmpty() && !entries.existsById(entryId)) {
            throw new EntryNotFoundException(entryId);
        }
        return history;
    }

    private EntryEntity requireLocked(UUID entryId) {
        return entries.findLockedById(entryId).orElseThrow(() -> new EntryNotFoundException(entryId));
    }

    private static void requireVisible(CategoryRef category) {
        if (category.hidden()) {
            throw new ValidationException("categoryId", "category is hidden");
        }
    }

    /** Поля после проверок пределов, в виде, в котором их хранит база. */
    private EntryFields fields(UUID categoryId, BigDecimal amount, LocalDate occurredOn, @Nullable String comment) {
        return new EntryFields(
                categoryId,
                EntryRules.amount(amount),
                ReportPeriod.requireDay("occurredOn", occurredOn, today()),
                EntryRules.comment(comment));
    }

    /**
     * Запись шаблона переносится на день, где уже есть запись того же шаблона, — ошибка запроса до изменения, без
     * прерывания транзакции. Гонку двух переносов ловит уникальный индекс ({@link #flushMoved}).
     */
    private void requireTemplateDayFree(UUID entryId, @Nullable UUID recurringId, LocalDate day) {
        if (recurringId != null && entries.existsByRecurringIdAndOccurredOnAndIdNot(recurringId, day, entryId)) {
            throw new ValidationException("occurredOn", TEMPLATE_DAY_TAKEN);
        }
    }

    /**
     * Сбрасывает правку в базу до записи журнала — последняя защита от гонки двух переносов: перенос записи шаблона
     * на день, где уже есть запись того же шаблона, нарушает {@code entry_recurring_id_occurred_on_key} — это ошибка
     * запроса по полю {@code occurredOn}. Остальные нарушения — ошибка в коде или схеме. После нарушения транзакция
     * PostgreSQL прервана: исключению дают всплыть.
     */
    private void flushMoved(EntryEntity entry) {
        try {
            entries.flush();
        } catch (DataIntegrityViolationException e) {
            var constraint = ConstraintViolations.constraintName(e);
            if (constraint.filter(RECURRING_DAY_KEY::equals).isPresent()) {
                throw new ValidationException("occurredOn", TEMPLATE_DAY_TAKEN);
            }
            throw new InternalException(
                    "entry " + entry.id() + " write violated constraint " + constraint.orElse("<unknown>"), e);
        }
    }

    private LocalDate today() {
        return salonTimeZone.today(clock);
    }

    /** Время с точностью {@code timestamptz} (микросекунды): что записано, то и прочитается. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
