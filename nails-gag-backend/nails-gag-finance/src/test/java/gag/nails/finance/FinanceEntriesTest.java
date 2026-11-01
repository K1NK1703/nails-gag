package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.paging.Paging;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.error.EntryNotFoundException;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.finance.model.QuickCombo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

@FinanceModuleTest
class FinanceEntriesTest extends FinanceTestSupport {

    private static final Paging ALL = new Paging(0, Paging.MAX_SIZE);

    @Test
    void addTakesKindFromCategory() {
        var tip = add(TIPS, "500");
        var materials = add(MATERIALS, "1500.5", LocalDate.of(2026, 9, 30), "Гель-лаки");

        assertThat(tip)
                .isEqualTo(new EntryView(
                        tip.id(),
                        EntryKind.INCOME,
                        TIPS,
                        "Чаевые",
                        new BigDecimal("500.00"),
                        TODAY,
                        null,
                        null,
                        clock.instant(),
                        clock.instant()));
        assertThat(materials.kind()).isEqualTo(EntryKind.EXPENSE);
        assertThat(materials.categoryName()).isEqualTo("Материалы");
        assertThat(materials.amount()).isEqualTo(new BigDecimal("1500.50"));
        assertThat(materials.occurredOn()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(materials.comment()).isEqualTo("Гель-лаки");
        assertThat(entries.get(tip.id(), MASTER)).isEqualTo(tip);
        assertThat(entries.get(materials.id(), MASTER)).isEqualTo(materials);
    }

    @Test
    void unknownCategoryIsNotFound() {
        var unknown = UUID.randomUUID();
        var entry = add(MATERIALS, "100");

        assertThatThrownBy(() -> add(unknown, "100"))
                .isInstanceOfSatisfying(
                        CategoryNotFoundException.class,
                        e -> assertThat(e.params()).containsEntry("categoryId", unknown));
        assertThatThrownBy(() -> entries.update(entry.id(), change(unknown, "100"), MASTER))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void hiddenCategoryCannotBeChosen() {
        var entry = add(MATERIALS, "100");
        categories.hide(RENT, MASTER);

        assertInvalid(() -> add(RENT, "30000"), "categoryId");
        assertInvalid(() -> entries.update(entry.id(), change(RENT, "100"), MASTER), "categoryId");
        assertThat(entries.get(entry.id(), MASTER)).isEqualTo(entry);
    }

    @Test
    void entryWithHiddenCategoryCanBeEdited() {
        var rent = add(RENT, "30000");
        categories.hide(RENT, MASTER);
        tick();

        var edited = entries.update(
                rent.id(), new UpdateEntry(RENT, new BigDecimal("32000"), TODAY, "С октября дороже"), MASTER);

        assertThat(edited.categoryId()).isEqualTo(RENT);
        assertThat(edited.categoryName()).isEqualTo("Аренда");
        assertThat(edited.amount()).isEqualTo(new BigDecimal("32000.00"));
        assertThat(edited.comment()).isEqualTo("С октября дороже");
        assertThat(edited.createdAt()).isEqualTo(rent.createdAt());
        assertThat(edited.updatedAt()).isEqualTo(clock.instant());
        // Вид записи не меняется: категория другого вида не подходит.
        assertInvalid(() -> entries.update(rent.id(), change(TIPS, "32000"), MASTER), "categoryId");
    }

    @Test
    void amountAndDateLimits() {
        assertInvalid(() -> add(MATERIALS, "0"), "amount");
        assertInvalid(() -> add(MATERIALS, "-1"), "amount");
        assertInvalid(() -> add(MATERIALS, "10000000000.00"), "amount");
        assertInvalid(() -> add(MATERIALS, "1.005"), "amount");
        assertThat(add(MATERIALS, "0.01").amount()).isEqualTo(new BigDecimal("0.01"));
        assertThat(add(MATERIALS, "9999999999.99").amount()).isEqualTo(new BigDecimal("9999999999.99"));
        // Нули после второго знака не мешают.
        assertThat(add(MATERIALS, "1.500").amount()).isEqualTo(new BigDecimal("1.50"));

        var earliest = LocalDate.of(2000, 1, 1);
        var latest = TODAY.plusDays(366);
        assertInvalid(() -> add(MATERIALS, "100", earliest.minusDays(1), null), "occurredOn");
        assertInvalid(() -> add(MATERIALS, "100", latest.plusDays(1), null), "occurredOn");
        assertInvalid(() -> add(MATERIALS, "100", LocalDate.MAX, null), "occurredOn");
        assertThat(add(MATERIALS, "100", earliest, null).occurredOn()).isEqualTo(earliest);
        var planned = add(MATERIALS, "100", latest, null);
        assertThat(planned.occurredOn()).isEqualTo(latest);

        // Правка проверяется так же.
        assertInvalid(() -> entries.update(planned.id(), change(MATERIALS, "0"), MASTER), "amount");
        assertInvalid(
                () -> entries.update(
                        planned.id(), new UpdateEntry(MATERIALS, BigDecimal.TEN, latest.plusDays(1), null), MASTER),
                "occurredOn");
        assertInvalid(() -> entries.add(new AddEntry(MATERIALS, null, TODAY, null), MASTER), "amount");
        assertInvalid(() -> entries.add(new AddEntry(MATERIALS, BigDecimal.TEN, null, null), MASTER), "occurredOn");
        assertInvalid(() -> entries.add(new AddEntry(null, BigDecimal.TEN, TODAY, null), MASTER), "categoryId");
    }

    @Test
    void commentUpTo500CodePoints() {
        // Эмодзи — один символ, хотя в Java это две единицы UTF-16.
        var longest = "💅".repeat(500);

        assertThat(add(MATERIALS, "100", TODAY, longest).comment()).isEqualTo(longest);
        assertInvalid(() -> add(MATERIALS, "100", TODAY, "💅".repeat(501)), "comment");
        assertInvalid(() -> add(MATERIALS, "100", TODAY, "x".repeat(501)), "comment");
        var blank = add(MATERIALS, "100", TODAY, "   ");
        assertThat(blank.comment()).isNull();
        // Пробелы по краям отбрасываются и в длину не входят.
        assertThat(add(MATERIALS, "100", TODAY, "  Гель-лаки \n").comment()).isEqualTo("Гель-лаки");
        assertThat(add(MATERIALS, "100", TODAY, " " + longest + " ").comment()).isEqualTo(longest);
        assertInvalid(
                () -> entries.update(
                        blank.id(), new UpdateEntry(MATERIALS, BigDecimal.TEN, TODAY, "x".repeat(501)), MASTER),
                "comment");
    }

    @Test
    void listFiltersAndPages() {
        var march = LocalDate.of(2025, 3, 1);
        var first = add(MATERIALS, "100", march.plusDays(9), null);
        tick();
        var second = add(RENT, "30000", march.plusDays(9), null);
        tick();
        var tip = add(TIPS, "500", march.plusDays(4), null);
        var last = add(MATERIALS, "200", march.plusDays(30), null);
        add(MATERIALS, "300", march.plusMonths(1), null);
        add(MATERIALS, "400", march.minusDays(1), null);
        var period = new EntryFilter(march, march.plusDays(30), null, null);

        var page = entries.list(period, new Paging(0, 2), MASTER);
        var next = entries.list(period, new Paging(1, 2), MASTER);

        assertThat(page.items()).extracting(EntryView::id).containsExactly(last.id(), second.id());
        assertThat(page.total()).isEqualTo(4);
        assertThat(next.items()).extracting(EntryView::id).containsExactly(first.id(), tip.id());
        assertThat(next.page()).isEqualTo(1);
        assertThat(next.size()).isEqualTo(2);
        assertThat(entries.list(new EntryFilter(march, march.plusDays(30), EntryKind.INCOME, null), ALL, MASTER)
                        .items())
                .containsExactly(tip);
        assertThat(entries.list(new EntryFilter(march, march.plusDays(30), null, MATERIALS), ALL, MASTER)
                        .items())
                .extracting(EntryView::id)
                .containsExactly(last.id(), first.id());
        var mismatch =
                entries.list(new EntryFilter(march, march.plusDays(30), EntryKind.INCOME, MATERIALS), ALL, MASTER);
        assertThat(mismatch.items()).isEmpty();
        assertThat(mismatch.total()).isZero();
        assertThat(entries.list(new EntryFilter(march.plusDays(9), march.plusDays(9), null, null), ALL, DEVELOPER)
                        .items())
                .extracting(EntryView::id)
                .containsExactly(second.id(), first.id());

        assertInvalid(() -> entries.list(new EntryFilter(march, march.minusDays(1), null, null), ALL, MASTER), "to");
        assertInvalid(() -> entries.list(new EntryFilter(march, march.plusDays(366), null, null), ALL, MASTER), "to");
        assertInvalid(() -> entries.list(new EntryFilter(null, march, null, null), ALL, MASTER), "from");
        assertInvalid(() -> entries.list(new EntryFilter(march, null, null, null), ALL, MASTER), "to");
    }

    @Test
    void deleteRemovesEntry() {
        var entry = add(MATERIALS, "100");
        var kept = add(MATERIALS, "200");

        entries.delete(entry.id(), MASTER);

        assertThatThrownBy(() -> entries.get(entry.id(), MASTER))
                .isInstanceOfSatisfying(
                        EntryNotFoundException.class,
                        e -> assertThat(e.params()).containsEntry("entryId", entry.id()));
        assertThat(entries.list(new EntryFilter(TODAY, TODAY, null, null), ALL, MASTER)
                        .items())
                .containsExactly(kept);
        assertThatThrownBy(() -> entries.delete(entry.id(), MASTER)).isInstanceOf(EntryNotFoundException.class);
        assertThatThrownBy(() -> entries.update(entry.id(), change(MATERIALS, "100"), MASTER))
                .isInstanceOf(EntryNotFoundException.class);
    }

    @Test
    void recentCombosAreFiveDistinctVisibleManual() {
        var gifts = category(EntryKind.EXPENSE, "Подарки");
        add(MATERIALS, "100");
        tick();
        add(RENT, "200");
        tick();
        add(MATERIALS, "100.00");
        tick();
        add(LEARNING, "300");
        tick();
        add(TOOLS, "400");
        tick();
        add(ADS, "500");
        tick();
        add(MATERIALS, "600");
        tick();
        add(gifts.id(), "700");
        categories.hide(gifts.id(), MASTER);
        tick();
        generatedByTemplate(RENT, "800", UUID.randomUUID(), TODAY);
        tick();
        add(TIPS, "900");

        assertThat(entries.recentCombos(EntryKind.EXPENSE, MASTER))
                .containsExactly(
                        new QuickCombo(MATERIALS, "Материалы", new BigDecimal("600.00")),
                        new QuickCombo(ADS, "Реклама", new BigDecimal("500.00")),
                        new QuickCombo(TOOLS, "Инструменты", new BigDecimal("400.00")),
                        new QuickCombo(LEARNING, "Обучение", new BigDecimal("300.00")),
                        new QuickCombo(MATERIALS, "Материалы", new BigDecimal("100.00")));
        assertInvalid(() -> entries.recentCombos(null, MASTER), "kind");
        assertThat(entries.recentCombos(EntryKind.INCOME, DEVELOPER))
                .containsExactly(new QuickCombo(TIPS, "Чаевые", new BigDecimal("900.00")));
    }

    @Test
    void readAndWriteRights() {
        var entry = add(MATERIALS, "100");

        assertThat(entries.get(entry.id(), DEVELOPER)).isEqualTo(entry);
        assertThat(entries.history(entry.id(), DEVELOPER)).hasSize(1);
        assertDenied(() -> entries.add(new AddEntry(MATERIALS, BigDecimal.TEN, TODAY, null), DEVELOPER));
        assertDenied(() -> entries.update(entry.id(), change(MATERIALS, "10"), DEVELOPER));
        assertDenied(() -> entries.delete(entry.id(), DEVELOPER));
        assertDenied(() -> entries.get(entry.id(), CLIENT));
        assertDenied(() -> entries.list(new EntryFilter(TODAY, TODAY, null, null), ALL, CLIENT));
        assertDenied(() -> entries.recentCombos(EntryKind.EXPENSE, CLIENT));
        assertDenied(() -> entries.history(entry.id(), CLIENT));
        assertThat(entries.get(entry.id(), MASTER)).isEqualTo(entry);
    }

    private static UpdateEntry change(UUID categoryId, String amount) {
        return new UpdateEntry(categoryId, new BigDecimal(amount), TODAY, null);
    }

    /** Записи шаблонов вставляются напрямую ({@link #generatedByTemplate}). */
    @Test
    void templateEntryCannotMoveOntoAnotherEntryOfTheSameTemplate() {
        var template = UUID.randomUUID();
        var first = generatedByTemplate(RENT, "30000", template, TODAY.minusMonths(1));
        generatedByTemplate(RENT, "30000", template, TODAY);
        var other = generatedByTemplate(RENT, "30000", UUID.randomUUID(), TODAY.minusDays(1));

        // Запись другого шаблона на тот же день — не помеха.
        assertThat(entries.update(other, change(RENT, "30000"), MASTER).occurredOn())
                .isEqualTo(TODAY);
        assertInvalid(() -> entries.update(first, change(RENT, "30000"), MASTER), "occurredOn");
        // Проверка — до изменения: транзакция цела, запись осталась на своём дне.
        assertThat(entries.get(first, MASTER).occurredOn()).isEqualTo(TODAY.minusMonths(1));
    }

    /**
     * Запись, созданная повторяющимся шаблоном: шаблонов в этой задаче ещё нет — строка вставляется напрямую, без
     * журнала.
     */
    private UUID generatedByTemplate(UUID categoryId, String amount, UUID recurringId, LocalDate day) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into finance.entry (id, kind, category_id, amount, occurred_on, recurring_id,
                                                   created_at, updated_at)
                        values (:id, 'EXPENSE', :categoryId, :amount, :day, :recurringId, :now, :now)""")
                .param("id", id)
                .param("categoryId", categoryId)
                .param("amount", new BigDecimal(amount))
                .param("day", day)
                .param("recurringId", recurringId)
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }
}
