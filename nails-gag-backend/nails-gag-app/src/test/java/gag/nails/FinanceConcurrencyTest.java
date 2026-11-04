package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.finance.FinanceCategories;
import gag.nails.finance.FinanceEntries;
import gag.nails.finance.FinanceMaintenance;
import gag.nails.finance.FinanceRecurring;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.CreateRecurring;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.error.CategoryNameTakenException;
import gag.nails.finance.error.EntryNotFoundException;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryAction;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Добавления и переименования категорий встречаются на блокировке списка вида, изменения записей — на блокировке
 * строки записи и разделяемой блокировке строки категории. Порядок задаётся не гонкой, а ожиданием блокировки
 * ({@link ConcurrencySupport#whileRowIsHeld}): второй гарантированно ждёт первого. База общая для тестов с тем же
 * контекстом: названия категорий — со случайным хвостом, а свои категории, записи и их журнал тест удаляет после
 * себя. Ночная задача в этих тестах обрабатывает все шаблоны базы, но других шаблонов тесты приложения не
 * заводят.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class FinanceConcurrencyTest {

    @Autowired
    FinanceCategories categories;

    @Autowired
    FinanceEntries entries;

    @Autowired
    FinanceRecurring recurring;

    @Autowired
    FinanceMaintenance maintenance;

    @Autowired
    Clock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    /** Чужие запросы к схеме finance, ждущие блокировку. */
    private static final String FINANCE_LOCK_WAITS = """
            select count(*) from pg_stat_activity
            where datname = current_database() and pid <> pg_backend_pid()
              and wait_event_type = 'Lock' and query like '%finance.%'""";

    /** Триггер теста встречной блокировки: останавливает правку шаблона сразу после вставки записи. */
    private static final String GATE_TRIGGER = "finance_concurrency_test_gate";

    private final Actor master = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());

    /** Названия категорий этого теста: по ним находятся и свои категории, вставленные в обход модуля. */
    private final List<String> names = new CopyOnWriteArrayList<>();

    /** Шаблоны этого теста: их записи и журнал этих записей тоже убираются. */
    private final List<UUID> templateIds = new CopyOnWriteArrayList<>();

    /** Записи этого теста, в том числе уже удалённые: их журнал тоже убирается. */
    private final List<UUID> entryIds = new CopyOnWriteArrayList<>();

    private ConcurrencySupport locks;

    @BeforeEach
    void financeLocks() {
        locks = new ConcurrencySupport(transaction, jdbc, "%finance.%");
    }

    /** Убирает за собой: журнал записей, записи и категории теста (в обход модуля — категории модуль не удаляет). */
    @AfterEach
    void removeOwnRows() {
        if (!templateIds.isEmpty()) {
            jdbc.sql("delete from finance.entry_change where coalesce(after, before) ->> 'recurringId' in (:ids)")
                    .param("ids", templateIds.stream().map(UUID::toString).toList())
                    .update();
            jdbc.sql("delete from finance.entry where recurring_id in (:ids)")
                    .param("ids", templateIds)
                    .update();
            jdbc.sql("delete from finance.recurring where id in (:ids)")
                    .param("ids", templateIds)
                    .update();
        }
        if (!entryIds.isEmpty()) {
            jdbc.sql("delete from finance.entry_change where entry_id in (:ids)")
                    .param("ids", entryIds)
                    .update();
            jdbc.sql("delete from finance.entry where id in (:ids)")
                    .param("ids", entryIds)
                    .update();
        }
        if (!names.isEmpty()) {
            jdbc.sql("delete from finance.category where name in (:names)")
                    .param("names", names)
                    .update();
        }
    }

    @Test
    void createWaitingForCreateOfTheSameNameIsRejected() throws Exception {
        var name = unique("Курсы");

        var created = locks.whileRowIsHeld(
                () -> create(EntryKind.EXPENSE, name), () -> create(EntryKind.EXPENSE, name.toUpperCase()));

        assertThat(created.failure()).isInstanceOf(CategoryNameTakenException.class);
        assertThat(categories.list(EntryKind.EXPENSE, master))
                .extracting(CategoryView::name)
                .containsOnlyOnce(name)
                .doesNotContain(name.toUpperCase());
    }

    @Test
    void concurrentCreatesGetDistinctPositions() throws Exception {
        var first = new AtomicReference<CategoryView>();
        var second = new AtomicReference<CategoryView>();

        var created = locks.whileRowIsHeld(
                () -> first.set(create(EntryKind.INCOME, unique("Подарки"))),
                () -> second.set(create(EntryKind.INCOME, unique("Мастер-классы"))));

        assertThat(created.failure()).isNull();
        assertThat(second.get().position()).isGreaterThan(first.get().position());
    }

    @Test
    void nameTakenByWriterBypassingTheLockIsTheSameError() throws Exception {
        var name = unique("Расходники");

        // Писатель в обход блокировки списка: проверка названия его незафиксированную строку не видит, вставка
        // встаёт на уникальный индекс и после его фиксации получает нарушение.
        var created = locks.whileRowIsHeld(
                () -> jdbc.sql("""
                                insert into finance.category (id, kind, name, hidden, position, created_at)
                                values (:id, 'EXPENSE', :name, false,
                                        (select coalesce(max(position), 0) + 1000 from finance.category), now())""")
                        .param("id", UUID.randomUUID())
                        .param("name", name)
                        .update(),
                () -> create(EntryKind.EXPENSE, name.toLowerCase()));

        assertThat(created.failure())
                .isInstanceOfSatisfying(
                        CategoryNameTakenException.class,
                        e -> assertThat(e.params()).containsEntry("name", name.toLowerCase()));
    }

    @Test
    void addWaitingForHideIsRejected() throws Exception {
        var category = create(EntryKind.EXPENSE, unique("Аренда"));

        var added = locks.whileRowIsHeld(() -> categories.hide(category.id(), master), () -> add(category.id()));

        assertThat(added.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("categoryId"));
    }

    @Test
    void templateEntryMovedOntoDayTakenAfterTheCheckIsRejected() throws Exception {
        var category = create(EntryKind.EXPENSE, unique("Аренда"));
        var template = UUID.randomUUID();
        var day = today();
        var moved = templateEntry(category.id(), template, day.minusMonths(1));

        // Писатель в обход модуля: предварительная проверка дня его незафиксированную строку не видит, перенос
        // встаёт на уникальный индекс и после его фиксации получает нарушение.
        var updated = locks.whileRowIsHeld(
                () -> templateEntry(category.id(), template, day),
                () -> entries.update(moved, new UpdateEntry(category.id(), BigDecimal.TEN, day, null), master));

        assertThat(updated.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("occurredOn"));
        assertThat(entries.get(moved, master).occurredOn()).isEqualTo(day.minusMonths(1));
        // Строка вставлена в обход модуля и журнала не имеет; отказавший перенос журнал тоже не написал.
        assertThat(entries.history(moved, master)).isEmpty();
    }

    @Test
    void nightlyJobSkipsTemplateHeldByAnEdit() throws Exception {
        var category = create(EntryKind.EXPENSE, unique("Подписка"));
        var today = today();
        var template = recurring.create(
                new CreateRecurring(category.id(), BigDecimal.TEN, null, today.getDayOfMonth(), today, null), master);
        templateIds.add(template.id());
        // Создание уже сделало сегодняшнюю запись; задаче нужен несозданный день.
        forgetToday(template.id(), today);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            // Правка держит строку шаблона, как держат её пауза, правка и удаление.
            var holding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("select id from finance.recurring where id = :id for update")
                        .param("id", template.id())
                        .query(UUID.class)
                        .single();
                held.countDown();
                await(release);
            }));
            try {
                assertThat(held.await(ConcurrencySupport.WAIT_SECONDS, TimeUnit.SECONDS))
                        .isTrue();
                // Задача не ждёт чужую блокировку: шаблон пропускается до следующего запуска.
                maintenance.createDueRecurringEntries();
                assertThat(templateDays(template.id())).isEmpty();
            } finally {
                release.countDown();
            }
            holding.get(ConcurrencySupport.WAIT_SECONDS, TimeUnit.SECONDS);
        }

        maintenance.createDueRecurringEntries();
        assertThat(templateDays(template.id())).containsExactly(today);
    }

    @Test
    void templateEditWithNewCategoryWaitsForReorder() throws Exception {
        var previous = create(EntryKind.EXPENSE, unique("Аренда"));
        var next = create(EntryKind.EXPENSE, unique("Кабинет"));
        var today = today();
        var day = today.getDayOfMonth();
        var template =
                recurring.create(new CreateRecurring(previous.id(), BigDecimal.TEN, null, day, today, null), master);
        templateIds.add(template.id());
        // Сегодняшний день снова не создан: правка создаст его запись по прежней категории, держа и её, и новую.
        forgetToday(template.id(), today);
        var order = categories.list(EntryKind.EXPENSE, master).stream()
                .map(CategoryView::id)
                .toList();

        // Перестановка держит список вида и все его категории; правка ждёт её целиком, а не встаёт накрест.
        var updated = locks.whileRowIsHeld(
                () -> categories.reorder(EntryKind.EXPENSE, order, master),
                () -> recurring.update(
                        template.id(), new UpdateRecurring(next.id(), BigDecimal.ONE, null, day, today, null), master));

        assertThat(updated.failure()).isNull();
        assertThat(jdbc.sql("select category_id from finance.entry where recurring_id = :id and occurred_on = :day")
                        .param("id", template.id())
                        .param("day", today)
                        .query(UUID.class)
                        .list())
                .containsExactly(previous.id());
        assertThat(recurring.list(master))
                .filteredOn(view -> view.id().equals(template.id()))
                .singleElement()
                .satisfies(view -> assertThat(view.categoryId()).isEqualTo(next.id()));
    }

    /**
     * Регрессия встречной блокировки. Правка шаблона со сменой категории держит прежнюю категорию (внешний ключ записи
     * за наступивший день) и затем берёт новую; перестановка блокирует все категории вида по id. Новая категория — с
     * меньшим id: перестановка успевает взять её и ждёт прежнюю, а правка ждала бы новую — накрест. Правка
     * останавливается сразу после вставки записи (триггер ждёт «ворота» — advisory-блокировку теста), перестановка
     * стартует в этот момент. С блокировкой списка вида перестановка ждёт её до начала, и обе завершаются.
     */
    @Test
    void templateEditChangingCategoryAndReorderDoNotDeadlock() throws Exception {
        var first = create(EntryKind.EXPENSE, unique("Кабинет"));
        var second = create(EntryKind.EXPENSE, unique("Аренда"));
        var next = first.id().compareTo(second.id()) < 0 ? first : second;
        var previous = next == first ? second : first;
        var today = today();
        var day = today.getDayOfMonth();
        var template =
                recurring.create(new CreateRecurring(previous.id(), BigDecimal.TEN, null, day, today, null), master);
        templateIds.add(template.id());
        forgetToday(template.id(), today);
        // Прежняя категория — первой: её позиция меняется, и перестановка обновляет её строку.
        var order = new ArrayList<UUID>();
        order.add(previous.id());
        categories.list(EntryKind.EXPENSE, master).stream()
                .map(CategoryView::id)
                .filter(id -> !id.equals(previous.id()))
                .forEach(order::add);
        var gate = new Random().nextLong();
        jdbc.sql("""
                        CREATE OR REPLACE FUNCTION finance.%1$s() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.recurring_id = '%2$s' THEN
                                PERFORM pg_advisory_xact_lock(%3$d);
                            END IF;
                            RETURN NEW;
                        END $$""".formatted(GATE_TRIGGER, template.id(), gate)).update();
        jdbc.sql("""
                        CREATE OR REPLACE TRIGGER %1$s AFTER INSERT ON finance.entry
                        FOR EACH ROW EXECUTE FUNCTION finance.%1$s()""".formatted(GATE_TRIGGER)).update();

        try (var gateHolder = dataSource.getConnection();
                var executor = Executors.newFixedThreadPool(2)) {
            gateHolder.createStatement().execute("select pg_advisory_lock(" + gate + ")");
            var edit = executor.submit(() -> ConcurrencySupport.outcome(() -> recurring.update(
                    template.id(), new UpdateRecurring(next.id(), BigDecimal.ONE, null, day, today, null), master)));
            awaitFinanceLockWaits(1);
            var reorder = executor.submit(
                    () -> ConcurrencySupport.outcome(() -> categories.reorder(EntryKind.EXPENSE, order, master)));
            awaitFinanceLockWaits(2);
            gateHolder.createStatement().execute("select pg_advisory_unlock(" + gate + ")");

            assertThat(edit.get(ConcurrencySupport.WAIT_SECONDS, TimeUnit.SECONDS)
                            .failure())
                    .isNull();
            assertThat(reorder.get(ConcurrencySupport.WAIT_SECONDS, TimeUnit.SECONDS)
                            .failure())
                    .isNull();
        } finally {
            dropGateTrigger();
        }
        assertThat(templateDays(template.id())).containsExactly(today);
        assertThat(categories.list(EntryKind.EXPENSE, master).getFirst().id()).isEqualTo(previous.id());
    }

    @Test
    void updateWaitingForDeleteFindsNothing() throws Exception {
        var category = create(EntryKind.EXPENSE, unique("Материалы"));
        var entry = add(category.id());

        var updated = locks.whileRowIsHeld(
                () -> entries.delete(entry.id(), master),
                () -> entries.update(
                        entry.id(), new UpdateEntry(category.id(), BigDecimal.ONE, today(), null), master));

        assertThat(updated.failure()).isInstanceOf(EntryNotFoundException.class);
        assertThat(entries.history(entry.id(), master))
                .extracting(EntryChangeView::action)
                .containsExactly(EntryAction.CREATE, EntryAction.DELETE);
    }

    /** Запись повторяющегося шаблона — в обход модуля: шаблонов ещё нет. */
    private UUID templateEntry(UUID categoryId, UUID recurringId, LocalDate day) {
        var id = UUID.randomUUID();
        entryIds.add(id);
        jdbc.sql("""
                        insert into finance.entry (id, kind, category_id, amount, occurred_on, recurring_id,
                                                   created_at, updated_at)
                        values (:id, 'EXPENSE', :categoryId, 10, :day, :recurringId, now(), now())""")
                .param("id", id)
                .param("categoryId", categoryId)
                .param("day", day)
                .param("recurringId", recurringId)
                .update();
        return id;
    }

    /** Ждёт, пока столько чужих запросов к схеме finance встанут на блокировку. */
    private void awaitFinanceLockWaits(int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ConcurrencySupport.WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.sql(FINANCE_LOCK_WAITS).query(Integer.class).single();
            if (waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fewer than " + count + " finance queries are waiting for a lock");
    }

    private void dropGateTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON finance.entry".formatted(GATE_TRIGGER))
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS finance.%s()".formatted(GATE_TRIGGER)).update();
    }

    /** Как будто сегодняшний день шаблона ещё не создан: его запись и журнал удаляются, прогресс — на вчера. */
    private void forgetToday(UUID templateId, LocalDate today) {
        var journal = "delete from finance.entry_change"
                + " where entry_id in (select id from finance.entry where recurring_id = :id)";
        jdbc.sql(journal).param("id", templateId).update();
        jdbc.sql("delete from finance.entry where recurring_id = :id")
                .param("id", templateId)
                .update();
        jdbc.sql("update finance.recurring set generated_through = :day where id = :id")
                .param("day", today.minusDays(1))
                .param("id", templateId)
                .update();
    }

    private List<LocalDate> templateDays(UUID templateId) {
        return jdbc.sql("select occurred_on from finance.entry where recurring_id = :id order by occurred_on")
                .param("id", templateId)
                .query(LocalDate.class)
                .list();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(ConcurrencySupport.WAIT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private EntryView add(UUID categoryId) {
        var entry = entries.add(new AddEntry(categoryId, BigDecimal.TEN, today(), null), master);
        entryIds.add(entry.id());
        return entry;
    }

    private CategoryView create(EntryKind kind, String name) {
        return categories.create(new CreateCategory(kind, name), master);
    }

    private LocalDate today() {
        return salonTimeZone.dayOf(Instant.now(clock));
    }

    private String unique(String name) {
        var unique = name + " " + UUID.randomUUID().toString().substring(0, 8);
        names.add(unique);
        return unique;
    }
}
