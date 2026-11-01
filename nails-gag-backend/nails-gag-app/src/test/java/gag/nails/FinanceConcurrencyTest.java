package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.finance.FinanceCategories;
import gag.nails.finance.FinanceEntries;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.UpdateEntry;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
 * себя.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class FinanceConcurrencyTest {

    @Autowired
    FinanceCategories categories;

    @Autowired
    FinanceEntries entries;

    @Autowired
    Clock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());

    /** Названия категорий этого теста: по ним находятся и свои категории, вставленные в обход модуля. */
    private final List<String> names = new CopyOnWriteArrayList<>();

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
