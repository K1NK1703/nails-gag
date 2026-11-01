package gag.nails.finance.internal.entry;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** Наибольшая сумма — предел {@code numeric(12,2)}. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** Знаков после запятой у суммы — как в {@code numeric(12,2)}. */
    private static final int AMOUNT_SCALE = 2;

    /** Предел длины комментария — как в {@code CHECK} колонки {@code comment}. */
    private static final int MAX_COMMENT_LENGTH = 500;

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
        var now = now();
        entry.apply(fields, now);
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
                requireAmount(amount),
                ReportPeriod.requireDay("occurredOn", occurredOn, today()),
                normalizeComment(comment));
    }

    /**
     * Сумма больше нуля, не больше {@link #MAX_AMOUNT}, не больше двух значащих знаков после запятой — с ровно двумя
     * знаками, как её вернёт база: иначе {@code 1500} и {@code 1500.00} были бы разными суммами для журнала.
     */
    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new ValidationException("amount", "must be greater than 0");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException("amount", "must be at most " + MAX_AMOUNT);
        }
        if (amount.stripTrailingZeros().scale() > AMOUNT_SCALE) {
            throw new ValidationException("amount", "must have at most " + AMOUNT_SCALE + " decimal places");
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * Пустой комментарий — его отсутствие. Длина — в символах (кодовых точках), как {@code char_length} в
     * {@code CHECK} базы: эмодзи — один символ, хотя в Java это две единицы UTF-16.
     */
    private static @Nullable String normalizeComment(@Nullable String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        if (comment.codePointCount(0, comment.length()) > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        return comment;
    }

    private LocalDate today() {
        return salonTimeZone.today(clock);
    }

    /** Время с точностью {@code timestamptz} (микросекунды): что записано, то и прочитается. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
