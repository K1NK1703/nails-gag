package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.InternalException;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.model.EntryAction;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntrySnapshot;
import gag.nails.finance.model.EntryView;
import gag.nails.finance.model.QuickCombo;
import gag.nails.finance.model.RecurringView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Записи повторяющихся шаблонов ({@link FinanceMaintenance}). На настоящих транзакциях: задача обрабатывает каждый
 * шаблон в своей транзакции и внутри чужой не работает, поэтому тест фиксирует свои данные и после себя удаляет
 * только их — свои шаблоны, их записи и журнал, свои категории. Стартовые категории тест не меняет. Часы стоят на
 * 1 октября 2026; тест переводит их на нужный день салона.
 */
@FinanceModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecurringEntriesTest extends RecurringTestSupport {

    /** Имя триггера и его функции в схеме finance: ставится одним тестом и снимается после каждого. */
    private static final String FAILURE_TRIGGER = "recurring_entries_test_fail_insert";

    /** Сумма, на которой триггер теста сбоя отказывает во вставке записи. */
    private static final String FAILING_AMOUNT = "666.66";

    private static final LocalDate OCT_1 = TODAY;
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Autowired
    TransactionTemplate transaction;

    private final List<UUID> templates = new CopyOnWriteArrayList<>();
    private final List<UUID> manualEntries = new CopyOnWriteArrayList<>();
    private final List<UUID> ownCategories = new CopyOnWriteArrayList<>();

    /** Сначала снимается триггер теста сбоя (если ставился), затем удаляются свои данные. */
    @AfterEach
    void cleanUp() {
        jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON finance.entry".formatted(FAILURE_TRIGGER))
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS finance.%s()".formatted(FAILURE_TRIGGER))
                .update();
        if (!templates.isEmpty()) {
            var ids = templates.stream().map(UUID::toString).toList();
            jdbc.sql("delete from finance.entry_change where coalesce(after, before) ->> 'recurringId' in (:ids)")
                    .param("ids", ids)
                    .update();
            jdbc.sql("delete from finance.entry where recurring_id in (:ids)")
                    .param("ids", templates)
                    .update();
            jdbc.sql("delete from finance.recurring where id in (:ids)")
                    .param("ids", templates)
                    .update();
        }
        if (!manualEntries.isEmpty()) {
            jdbc.sql("delete from finance.entry_change where entry_id in (:ids)")
                    .param("ids", manualEntries)
                    .update();
            jdbc.sql("delete from finance.entry where id in (:ids)")
                    .param("ids", manualEntries)
                    .update();
        }
        if (!ownCategories.isEmpty()) {
            jdbc.sql("delete from finance.category where id in (:ids)")
                    .param("ids", ownCategories)
                    .update();
        }
    }

    @Test
    void createsEntriesForDueDays() {
        var rent = template(RENT, "30000", 5, OCT_1, null);

        run();
        assertThat(days(rent)).isEmpty();

        today(OCT_5);
        run();
        assertThat(days(rent)).containsExactly(OCT_5);
        var entry = entries.get(entryIds(rent).getFirst(), MASTER);
        assertThat(entry)
                .isEqualTo(new EntryView(
                        entry.id(),
                        EntryKind.EXPENSE,
                        RENT,
                        "Аренда",
                        new BigDecimal("30000.00"),
                        OCT_5,
                        null,
                        rent.id(),
                        clock.instant(),
                        clock.instant()));

        today(OCT_5.plusDays(1));
        run();
        assertThat(days(rent)).containsExactly(OCT_5);

        today(NOV_5);
        run();
        assertThat(days(rent)).containsExactly(OCT_5, NOV_5);
    }

    @Test
    void catchesUpMissedDays() {
        var rent = template(RENT, "30000", 10, LocalDate.of(2026, 7, 1), null);

        run();
        assertThat(days(rent))
                .containsExactly(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 10));

        // Задача не запускалась два с половиной месяца.
        today(LocalDate.of(2026, 12, 15));
        run();
        assertThat(days(rent))
                .containsExactly(
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 11, 10),
                        LocalDate.of(2026, 12, 10));
    }

    @Test
    void day31InFebruaryIsLastDay() {
        var thirtyFirst = template(RENT, "30000", 31, LocalDate.of(2027, 1, 1), null);
        var thirtieth = template(MATERIALS, "100", 30, LocalDate.of(2027, 1, 1), null);

        today(LocalDate.of(2027, 3, 31));
        run();

        assertThat(days(thirtyFirst))
                .containsExactly(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31));
        assertThat(days(thirtieth))
                .containsExactly(LocalDate.of(2027, 1, 30), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 30));
    }

    @Test
    void newTemplateCreatesDueEntriesAtOnce() {
        today(OCT_5);

        var dueToday = template(RENT, "30000", 5, OCT_1, null);
        var startedEarlier = template(MATERIALS, "100", 10, LocalDate.of(2026, 8, 1), null);

        // Без запуска задачи.
        assertThat(days(dueToday)).containsExactly(OCT_5);
        assertThat(days(startedEarlier)).containsExactly(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 10));
    }

    @Test
    void changingDayToPassedDayWithoutThisMonthsEntryCreatesItAtOnce() {
        var rent = template(RENT, "30000", 25, OCT_1, null);
        today(LocalDate.of(2026, 10, 10));

        recurring.update(rent.id(), change(rent, "30000", 5, OCT_1), MASTER);

        // Без запуска задачи.
        assertThat(days(rent)).containsExactly(OCT_5);
    }

    @Test
    void changingDayTogetherWithStartsOnDoesNotSkipMonth() {
        var rent = template(RENT, "30000", 5, NOV_5.withDayOfMonth(1), null);
        today(LocalDate.of(2026, 10, 10));
        run();

        // По прежнему startsOn октябрьский день не входил в срок: записи месяца нет, и месяц не пропускается.
        recurring.update(rent.id(), change(rent, "30000", 20, OCT_1), MASTER);
        assertThat(days(rent)).isEmpty();
        today(LocalDate.of(2026, 10, 20));
        run();

        assertThat(days(rent)).containsExactly(LocalDate.of(2026, 10, 20));
    }

    @Test
    void changingDayInFebruary() {
        var thirtyFirst = template(RENT, "30000", 31, LocalDate.of(2027, 1, 1), null);
        var twentyNinth = template(MATERIALS, "100", 29, LocalDate.of(2027, 1, 1), null);
        var february28 = LocalDate.of(2027, 2, 28);
        today(february28);
        run();
        assertThat(days(thirtyFirst)).containsExactly(LocalDate.of(2027, 1, 31), february28);
        assertThat(days(twentyNinth)).containsExactly(LocalDate.of(2027, 1, 29), february28);

        // Прогресс на 28-м, запись февраля уже есть: следующая — в новое число марта, второй в феврале нет.
        recurring.update(thirtyFirst.id(), change(thirtyFirst, "30000", 30, thirtyFirst.startsOn()), MASTER);
        recurring.update(twentyNinth.id(), change(twentyNinth, "100", 31, twentyNinth.startsOn()), MASTER);
        today(LocalDate.of(2027, 3, 31));
        run();

        assertThat(days(thirtyFirst)).containsExactly(LocalDate.of(2027, 1, 31), february28, LocalDate.of(2027, 3, 30));
        assertThat(days(twentyNinth)).containsExactly(LocalDate.of(2027, 1, 29), february28, LocalDate.of(2027, 3, 31));
    }

    @Test
    void changingDayInLeapFebruaryBeforeItsEntry() {
        var february28 = LocalDate.of(2028, 2, 28);
        today(february28);
        var rent = template(RENT, "30000", 31, LocalDate.of(2028, 1, 1), null);
        assertThat(days(rent)).containsExactly(LocalDate.of(2028, 1, 31));

        // Запись февраля (29-е) ещё впереди: новое число 30 в феврале — тоже 29-е.
        recurring.update(rent.id(), change(rent, "30000", 30, rent.startsOn()), MASTER);
        today(february28.plusDays(1));
        run();

        assertThat(days(rent)).containsExactly(LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29));
    }

    @Test
    void resumeBeforeTheJobPassesTheDayKeepsTheDay() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        recurring.pause(rent.id(), MASTER);

        // 5-е: задача ещё не проходила этот день на паузе.
        today(OCT_5);
        recurring.resume(rent.id(), MASTER);
        run();

        assertThat(days(rent)).containsExactly(OCT_5);
    }

    @Test
    void resumeAfterTheJobPassedTheDaySkipsIt() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        recurring.pause(rent.id(), MASTER);

        // 5-е: задача уже прошла этот день на паузе.
        today(OCT_5);
        run();
        recurring.resume(rent.id(), MASTER);
        run();
        assertThat(days(rent)).isEmpty();

        today(NOV_5);
        run();
        assertThat(days(rent)).containsExactly(NOV_5);
    }

    @Test
    void extendingEndsOnCatchesNothingUp() {
        var rent = template(RENT, "30000", 5, OCT_1, LocalDate.of(2026, 10, 3));
        today(LocalDate.of(2026, 11, 10));
        run();

        recurring.update(rent.id(), new UpdateRecurring(RENT, new BigDecimal("30000"), null, 5, OCT_1, null), MASTER);
        run();
        assertThat(days(rent)).isEmpty();

        today(LocalDate.of(2026, 12, 5));
        run();
        assertThat(days(rent)).containsExactly(LocalDate.of(2026, 12, 5));
    }

    @Test
    void pausedAndEndedCreateNothing() {
        var paused = template(RENT, "30000", 5, OCT_1, null);
        var ended = template(MATERIALS, "100", 5, OCT_1, LocalDate.of(2026, 10, 31));
        recurring.pause(paused.id(), MASTER);

        today(OCT_5);
        run();
        today(NOV_5.plusDays(1));
        run();

        assertThat(days(paused)).isEmpty();
        assertThat(days(ended)).containsExactly(OCT_5);

        // После снятия паузы пропущенное не догоняется: следующая запись — в декабре.
        recurring.resume(paused.id(), MASTER);
        run();
        assertThat(days(paused)).isEmpty();
        today(LocalDate.of(2026, 12, 5));
        run();
        assertThat(days(paused)).containsExactly(LocalDate.of(2026, 12, 5));
        assertThat(days(ended)).containsExactly(OCT_5);
    }

    @Test
    void pauseWithoutRunsBetweenSkipsPausedDaysToo() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        recurring.pause(rent.id(), MASTER);

        // Задача ни разу не запускалась на паузе: снятие паузы всё равно не догоняет её дни.
        today(NOV_5.plusDays(1));
        recurring.resume(rent.id(), MASTER);
        run();

        assertThat(days(rent)).isEmpty();
    }

    @Test
    void pauseFirstCreatesDaysBeforeIt() {
        var rent = template(RENT, "30000", 5, OCT_1, null);

        // Задача ещё не запускалась после 5-го: запись за наступивший день создаёт сама пауза.
        today(OCT_5.plusDays(1));
        recurring.pause(rent.id(), MASTER);

        assertThat(days(rent)).containsExactly(OCT_5);
    }

    @Test
    void rerunCreatesNoDuplicates() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);

        run();
        run();
        assertThat(days(rent)).containsExactly(OCT_5);

        // Прогресс потерян (например, запуск прервался после вставки): вставка всё равно не дублирует.
        jdbc.sql("update finance.recurring set generated_through = null where id = :id")
                .param("id", rent.id())
                .update();
        run();
        assertThat(days(rent)).containsExactly(OCT_5);
        assertThat(entries.history(entryIds(rent).getFirst(), MASTER)).hasSize(1);
        // Занятый день не сбой: прогресс сдвинут, шаблон не ждёт следующего запуска.
        assertThat(generatedThrough(rent)).isEqualTo(OCT_5);
    }

    @Test
    void deletedGeneratedEntryIsNotRecreated() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();

        entries.delete(entryIds(rent).getFirst(), MASTER);
        run();
        today(OCT_5.plusDays(1));
        run();

        assertThat(days(rent)).isEmpty();
    }

    @Test
    void movedGeneratedEntryIsNotRecreated() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();
        var moved = OCT_5.plusDays(2);

        entries.update(entryIds(rent).getFirst(), new UpdateEntry(RENT, new BigDecimal("30000"), moved, null), MASTER);
        run();
        today(moved);
        run();

        assertThat(days(rent)).containsExactly(moved);
    }

    @Test
    void templateChangeAffectsOnlyFutureEntries() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();

        recurring.update(
                rent.id(),
                new UpdateRecurring(MATERIALS, new BigDecimal("32000"), "Новая цена", 5, OCT_1, null),
                MASTER);
        today(NOV_5);
        run();

        assertThat(entriesOf(rent))
                .extracting(EntryView::occurredOn, EntryView::categoryId, EntryView::amount, EntryView::comment)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(OCT_5, RENT, new BigDecimal("30000.00"), null),
                        org.assertj.core.groups.Tuple.tuple(
                                NOV_5, MATERIALS, new BigDecimal("32000.00"), "Новая цена"));
    }

    @Test
    void daysDueBeforeChangeKeepOldFields() {
        var rent = template(RENT, "30000", 5, OCT_1, null);

        // Задача ещё не создала запись за наступившее 5-е: её создаёт правка — по прежним полям.
        today(OCT_5.plusDays(1));
        recurring.update(rent.id(), change(rent, "32000", 5, OCT_1), MASTER);

        assertThat(entriesOf(rent)).singleElement().satisfies(entry -> {
            assertThat(entry.occurredOn()).isEqualTo(OCT_5);
            assertThat(entry.amount()).isEqualTo(new BigDecimal("30000.00"));
        });
    }

    @Test
    void generatedEntriesAreJournaledAsSystem() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();
        var entryId = entryIds(rent).getFirst();

        assertThat(entries.history(entryId, MASTER))
                .singleElement()
                .isEqualTo(new EntryChangeView(
                        entries.history(entryId, MASTER).getFirst().id(),
                        entryId,
                        EntryAction.CREATE,
                        null,
                        new EntrySnapshot(
                                EntryKind.EXPENSE, RENT, "Аренда", new BigDecimal("30000.00"), OCT_5, null, rent.id()),
                        null,
                        clock.instant()));
    }

    @Test
    void deletingTemplateKeepsEntries() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();

        recurring.delete(rent.id(), MASTER);
        today(NOV_5);
        run();

        assertThat(entriesOf(rent)).singleElement().satisfies(entry -> {
            assertThat(entry.occurredOn()).isEqualTo(OCT_5);
            assertThat(entry.recurringId()).isEqualTo(rent.id());
        });
    }

    @Test
    void generatedEntriesAreNotQuickCombos() {
        var rent = template(RENT, "777.77", 5, OCT_1, null);
        today(OCT_5);
        run();

        assertThat(days(rent)).containsExactly(OCT_5);
        assertThat(entries.recentCombos(EntryKind.EXPENSE, MASTER))
                .doesNotContain(new QuickCombo(RENT, "Аренда", new BigDecimal("777.77")));
    }

    @Test
    void changingDayAfterThisMonthsEntryDoesNotDuplicate() {
        var rent = template(RENT, "30000", 5, OCT_1, null);
        today(OCT_5);
        run();

        today(LocalDate.of(2026, 10, 10));
        recurring.update(rent.id(), change(rent, "30000", 20, OCT_1), MASTER);
        today(LocalDate.of(2026, 10, 20));
        run();
        assertThat(days(rent)).containsExactly(OCT_5);

        today(LocalDate.of(2026, 11, 20));
        run();
        assertThat(days(rent)).containsExactly(OCT_5, LocalDate.of(2026, 11, 20));
    }

    @Test
    void changingDayBeforeThisMonthsEntryDoesNotSkipMonth() {
        var later = template(RENT, "30000", 25, OCT_1, null);
        var earlier = template(MATERIALS, "100", 25, OCT_1, null);
        today(LocalDate.of(2026, 10, 10));
        run();

        // Новое число ещё впереди — запись в него в этом месяце.
        recurring.update(later.id(), change(later, "30000", 15, OCT_1), MASTER);
        // Новое число уже прошло — ближайший запуск создаст запись этим числом.
        recurring.update(earlier.id(), change(earlier, "100", 5, OCT_1), MASTER);
        run();
        assertThat(days(earlier)).containsExactly(OCT_5);
        assertThat(days(later)).isEmpty();

        today(LocalDate.of(2026, 10, 25));
        run();
        assertThat(days(later)).containsExactly(LocalDate.of(2026, 10, 15));
        assertThat(days(earlier)).containsExactly(OCT_5);

        today(LocalDate.of(2026, 11, 30));
        run();
        assertThat(days(later)).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15));
        assertThat(days(earlier)).containsExactly(OCT_5, NOV_5);
    }

    @Test
    void movingStartsOnBackCatchesNothingUp() {
        var rent = template(RENT, "30000", 5, NOV_5.withDayOfMonth(1), null);
        today(OCT_5.plusDays(1));
        run();

        recurring.update(rent.id(), change(rent, "30000", 5, LocalDate.of(2026, 8, 1)), MASTER);
        run();
        assertThat(days(rent)).isEmpty();

        today(NOV_5);
        run();
        assertThat(days(rent)).containsExactly(NOV_5);
    }

    @Test
    void hiddenCategoryStillGetsEntries() {
        var subscription = categories.create(
                new CreateCategory(
                        EntryKind.EXPENSE,
                        "Подписка " + UUID.randomUUID().toString().substring(0, 8)),
                MASTER);
        ownCategories.add(subscription.id());
        var template = template(subscription.id(), "990", 5, OCT_1, null);
        categories.hide(subscription.id(), MASTER);

        today(OCT_5);
        run();

        assertThat(days(template)).containsExactly(OCT_5);
    }

    @Test
    void failureOfOneTemplateDoesNotStopOthers() {
        var failing = template(RENT, FAILING_AMOUNT, 5, OCT_1, null);
        var working = template(MATERIALS, "100", 5, OCT_1, null);
        failInsertsOf(FAILING_AMOUNT);
        today(OCT_5);

        run();

        assertThat(days(failing)).isEmpty();
        assertThat(days(working)).containsExactly(OCT_5);
        // Прогресс сбойного шаблона остался на дне создания: следующий запуск его догонит.
        assertThat(generatedThrough(failing)).isEqualTo(OCT_1);
        jdbc.sql("DROP TRIGGER %1$s ON finance.entry".formatted(FAILURE_TRIGGER))
                .update();
        today(OCT_5.plusDays(1));
        run();
        assertThat(days(failing)).containsExactly(OCT_5);
        assertThat(days(working)).containsExactly(OCT_5);
    }

    @Test
    void maintenanceRefusesCallerTransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> maintenance.createDueRecurringEntries()))
                .isInstanceOf(InternalException.class);
    }

    @Override
    RecurringView template(
            UUID categoryId, String amount, int dayOfMonth, LocalDate startsOn, @Nullable LocalDate endsOn) {
        var created = super.template(categoryId, amount, dayOfMonth, startsOn, endsOn);
        templates.add(created.id());
        return created;
    }

    private int run() {
        return maintenance.createDueRecurringEntries();
    }

    /** Дни записей шаблона по порядку. */
    private List<LocalDate> days(RecurringView template) {
        return jdbc.sql("select occurred_on from finance.entry where recurring_id = :id order by occurred_on")
                .param("id", template.id())
                .query(LocalDate.class)
                .list();
    }

    private @Nullable LocalDate generatedThrough(RecurringView template) {
        return jdbc.sql("select generated_through from finance.recurring where id = :id")
                .param("id", template.id())
                .query(LocalDate.class)
                .optional()
                .orElse(null);
    }

    private List<UUID> entryIds(RecurringView template) {
        return jdbc.sql("select id from finance.entry where recurring_id = :id order by occurred_on")
                .param("id", template.id())
                .query(UUID.class)
                .list();
    }

    private List<EntryView> entriesOf(RecurringView template) {
        return entryIds(template).stream().map(id -> entries.get(id, MASTER)).toList();
    }

    /** Ставит триггер, который отказывает во вставке записи с этой суммой: сбой одного шаблона. */
    private void failInsertsOf(String amount) {
        jdbc.sql("""
                        CREATE OR REPLACE FUNCTION finance.%1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.amount = %2$s THEN
                                RAISE EXCEPTION 'test failure for amount %%', NEW.amount;
                            END IF;
                            RETURN NEW;
                        END $$""".formatted(FAILURE_TRIGGER, amount)).update();
        jdbc.sql("""
                        CREATE OR REPLACE TRIGGER %1$s BEFORE INSERT ON finance.entry
                        FOR EACH ROW EXECUTE FUNCTION finance.%1$s()""".formatted(FAILURE_TRIGGER)).update();
    }
}
