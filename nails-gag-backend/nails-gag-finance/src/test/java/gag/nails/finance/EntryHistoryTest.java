package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.finance.command.RenameCategory;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.error.EntryNotFoundException;
import gag.nails.finance.model.EntryAction;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntrySnapshot;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@FinanceModuleTest
class EntryHistoryTest extends FinanceTestSupport {

    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    EntityManager entityManager;

    @Test
    void createUpdateDeleteAreJournaled() {
        var created = clock.instant();
        var entry = add(MATERIALS, "1500", YESTERDAY, "Гель-лаки");
        tick();
        var updated = clock.instant();
        entries.update(entry.id(), new UpdateEntry(RENT, new BigDecimal("30000.5"), TODAY, null), MASTER);
        tick();
        var deleted = clock.instant();
        // Актор без аккаунта (внутренний) попадает в журнал без аккаунта.
        entries.delete(entry.id(), Actor.master());

        var original = new EntrySnapshot(
                EntryKind.EXPENSE, MATERIALS, "Материалы", new BigDecimal("1500.00"), YESTERDAY, "Гель-лаки", null);
        var changed =
                new EntrySnapshot(EntryKind.EXPENSE, RENT, "Аренда", new BigDecimal("30000.50"), TODAY, null, null);
        // Снимки читаются из jsonb, а не из кэша сессии.
        entityManager.flush();
        entityManager.clear();
        var history = entries.history(entry.id(), MASTER);
        assertThat(history)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("id")
                .containsExactly(
                        new EntryChangeView(
                                null, entry.id(), EntryAction.CREATE, null, original, MASTER_ACCOUNT, created),
                        new EntryChangeView(
                                null, entry.id(), EntryAction.UPDATE, original, changed, MASTER_ACCOUNT, updated),
                        new EntryChangeView(null, entry.id(), EntryAction.DELETE, changed, null, null, deleted));
        assertThat(history).extracting(EntryChangeView::id).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(history.get(1).after().amount()).isEqualTo(new BigDecimal("30000.50"));
    }

    @Test
    void noChangeWritesNothing() {
        var entry = add(MATERIALS, "1500", YESTERDAY, null);
        tick();

        // Те же поля: сумма с другим числом нулей, пустой комментарий — то же, что его отсутствие.
        var same = entries.update(
                entry.id(), new UpdateEntry(MATERIALS, new BigDecimal("1500.0"), YESTERDAY, "  "), MASTER);

        assertThat(same).isEqualTo(entry);
        assertThat(same.updatedAt()).isEqualTo(entry.createdAt());
        assertThat(entries.history(entry.id(), MASTER))
                .singleElement()
                .extracting(EntryChangeView::action)
                .isEqualTo(EntryAction.CREATE);
    }

    @Test
    void historyOfDeletedEntryIsKept() {
        var entry = add(MATERIALS, "1500");
        entries.delete(entry.id(), MASTER);

        assertThatThrownBy(() -> entries.get(entry.id(), MASTER)).isInstanceOf(EntryNotFoundException.class);
        assertThat(entries.history(entry.id(), DEVELOPER))
                .extracting(EntryChangeView::action)
                .containsExactly(EntryAction.CREATE, EntryAction.DELETE);
        var unknown = UUID.randomUUID();
        assertThatThrownBy(() -> entries.history(unknown, MASTER))
                .isInstanceOfSatisfying(
                        EntryNotFoundException.class,
                        e -> assertThat(e.params()).containsEntry("entryId", unknown));
    }

    @Test
    void categoryNameInSnapshotSurvivesRename() {
        var entry = add(MATERIALS, "1500");
        categories.rename(MATERIALS, new RenameCategory("Расходники"), MASTER);
        tick();
        entries.update(entry.id(), new UpdateEntry(MATERIALS, new BigDecimal("1600"), TODAY, null), MASTER);

        var history = entries.history(entry.id(), MASTER);

        assertThat(history.getFirst().after().categoryName()).isEqualTo("Материалы");
        // Правка после переименования: «было» — название на момент правки.
        assertThat(history.get(1).before().categoryName()).isEqualTo("Расходники");
        assertThat(history.get(1).after().categoryName()).isEqualTo("Расходники");
        assertThat(entries.get(entry.id(), MASTER).categoryName()).isEqualTo("Расходники");
    }

    @Test
    void snapshotsAreStoredAsJsonObjects() {
        var entry = add(TIPS, "500", YESTERDAY, "От Марии");
        // Журнал читается в обход JPA: несброшенная вставка в той же транзакции иначе не видна.
        entityManager.flush();

        var query = """
                        select jsonb_typeof(after) as type, after ->> 'kind' as kind,
                               after ->> 'categoryId' as category_id, after ->> 'categoryName' as category_name,
                               after ->> 'amount' as amount, after ->> 'occurredOn' as occurred_on,
                               after ->> 'comment' as comment, after -> 'recurringId' as recurring_id
                        from finance.entry_change where entry_id = :entryId""";
        var stored = jdbc.sql(query).param("entryId", entry.id()).query().singleRow();

        assertThat(stored)
                .containsEntry("type", "object")
                .containsEntry("kind", "INCOME")
                .containsEntry("category_id", TIPS.toString())
                .containsEntry("category_name", "Чаевые")
                .containsEntry("amount", "500.00")
                .containsEntry("occurred_on", YESTERDAY.toString())
                .containsEntry("comment", "От Марии");
        // Пустое поле — JSON null или его отсутствие, но не строка.
        assertThat(stored.get("recurring_id"))
                .satisfiesAnyOf(
                        value -> assertThat(value).isNull(),
                        value -> assertThat(value).hasToString("null"));
    }
}
