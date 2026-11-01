package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.AccessDeniedException;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.RenameCategory;
import gag.nails.finance.error.CategoryNameTakenException;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

@FinanceModuleTest
class FinanceCategoriesTest extends FinanceTestSupport {

    @Test
    void startsWithDefaultCategories() {
        var expenses = categories.list(EntryKind.EXPENSE, MASTER);
        var incomes = categories.list(EntryKind.INCOME, MASTER);

        assertThat(expenses)
                .extracting(CategoryView::name)
                .containsExactly("Материалы", "Аренда", "Обучение", "Инструменты", "Реклама", "Прочее");
        assertThat(incomes).extracting(CategoryView::name).containsExactly("Чаевые", "Продажа товаров", "Прочее");
        assertThat(expenses).allSatisfy(category -> {
            assertThat(category.kind()).isEqualTo(EntryKind.EXPENSE);
            assertThat(category.hidden()).isFalse();
        });
        assertThat(incomes).allSatisfy(category -> {
            assertThat(category.kind()).isEqualTo(EntryKind.INCOME);
            assertThat(category.hidden()).isFalse();
        });
        assertThat(expenses).extracting(CategoryView::position).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(expenses.getFirst().id()).isEqualTo(MATERIALS);
        assertThat(incomes.getFirst().id()).isEqualTo(TIPS);
    }

    @Test
    void createAppendsToTheEndOfItsKind() {
        var created = category(EntryKind.EXPENSE, "  Курсы  ");

        assertThat(created).isEqualTo(new CategoryView(created.id(), EntryKind.EXPENSE, "Курсы", false, 6));
        assertThat(categories.list(EntryKind.EXPENSE, MASTER)).last().isEqualTo(created);
        assertThat(categories.list(EntryKind.INCOME, MASTER))
                .extracting(CategoryView::id)
                .doesNotContain(created.id());
    }

    @Test
    void nameIsUniqueWithinKindIgnoringCase() {
        var courses = category(EntryKind.EXPENSE, "Курсы");

        assertNameTaken(() -> category(EntryKind.EXPENSE, "курсы"), "курсы");
        assertNameTaken(() -> category(EntryKind.EXPENSE, "  АРЕНДА "), "АРЕНДА");
        assertNameTaken(() -> categories.rename(courses.id(), new RenameCategory("материалы"), MASTER), "материалы");
        // Своё же название в другом регистре — не занято.
        assertThat(categories
                        .rename(courses.id(), new RenameCategory("КУРСЫ"), MASTER)
                        .name())
                .isEqualTo("КУРСЫ");
        assertThat(categories.list(EntryKind.EXPENSE, MASTER))
                .extracting(CategoryView::name)
                .containsOnlyOnce("КУРСЫ", "Материалы", "Аренда")
                .doesNotContain("курсы", "Курсы", "АРЕНДА", "материалы");
    }

    @Test
    void sameNameInOtherKindIsAllowed() {
        var income = category(EntryKind.INCOME, "Аренда");

        assertThat(income.kind()).isEqualTo(EntryKind.INCOME);
        assertThat(income.name()).isEqualTo("Аренда");
        assertThat(categories.list(EntryKind.EXPENSE, MASTER))
                .extracting(CategoryView::name)
                .contains("Аренда");
    }

    @Test
    void renameAndReorder() {
        var renamed = categories.rename(RENT, new RenameCategory("Аренда кабинета"), MASTER);

        assertThat(renamed).isEqualTo(new CategoryView(RENT, EntryKind.EXPENSE, "Аренда кабинета", false, 1));
        var before = ids(EntryKind.EXPENSE);
        var reversed = before.reversed();

        categories.reorder(EntryKind.EXPENSE, reversed, MASTER);

        var after = categories.list(EntryKind.EXPENSE, MASTER);
        assertThat(after).extracting(CategoryView::id).containsExactlyElementsOf(reversed);
        assertThat(after).extracting(CategoryView::position).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(after).filteredOn(c -> c.id().equals(RENT)).singleElement().satisfies(rent -> {
            assertThat(rent.name()).isEqualTo("Аренда кабинета");
        });
    }

    @Test
    void reorderNeedsTheWholeListOfTheKind() {
        var all = ids(EntryKind.EXPENSE);
        var missing = all.subList(1, all.size());
        var repeated = new ArrayList<>(all);
        repeated.set(0, all.get(1));
        var foreign = new ArrayList<>(all);
        foreign.set(0, TIPS);
        var unknown = new ArrayList<>(all);
        unknown.add(UUID.randomUUID());
        var withNull = new ArrayList<UUID>(all);
        withNull.add(null);

        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, missing, MASTER), "orderedIds");
        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, repeated, MASTER), "orderedIds");
        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, foreign, MASTER), "orderedIds");
        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, unknown, MASTER), "orderedIds");
        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, withNull, MASTER), "orderedIds");
        // Новая категория, которой нет в устаревшем списке, — тоже неполный список.
        category(EntryKind.EXPENSE, "Курсы");
        assertInvalid(() -> categories.reorder(EntryKind.EXPENSE, all, MASTER), "orderedIds");
        assertThat(ids(EntryKind.EXPENSE)).startsWith(all.toArray(UUID[]::new));
    }

    @Test
    void hiddenCategoryIsListedButMarked() {
        categories.hide(RENT, MASTER);
        categories.hide(RENT, MASTER);

        assertThat(categories.list(EntryKind.EXPENSE, MASTER))
                .filteredOn(CategoryView::hidden)
                .singleElement()
                .isEqualTo(new CategoryView(RENT, EntryKind.EXPENSE, "Аренда", true, 1));

        categories.show(RENT, MASTER);
        categories.show(RENT, MASTER);

        assertThat(categories.list(EntryKind.EXPENSE, MASTER)).noneMatch(CategoryView::hidden);
    }

    @Test
    void unknownCategoryIsNotFound() {
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> categories.hide(unknown, MASTER))
                .isInstanceOfSatisfying(
                        CategoryNotFoundException.class,
                        e -> assertThat(e.params()).containsEntry("categoryId", unknown));
        assertThatThrownBy(() -> categories.show(unknown, MASTER)).isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> categories.rename(unknown, new RenameCategory("Курсы"), MASTER))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void nameLengthInCodePoints() {
        // Эмодзи — один символ, хотя в Java это две единицы UTF-16: 60 символов — 120 единиц.
        var longest = "💅".repeat(60);

        assertThat(category(EntryKind.EXPENSE, longest).name()).isEqualTo(longest);
        assertInvalid(() -> category(EntryKind.INCOME, "💅".repeat(61)), "name");
        assertInvalid(() -> category(EntryKind.INCOME, "x".repeat(61)), "name");
        assertInvalid(() -> category(EntryKind.INCOME, "   "), "name");
        assertInvalid(() -> categories.rename(RENT, new RenameCategory("x".repeat(61)), MASTER), "name");
        assertInvalid(() -> categories.rename(RENT, new RenameCategory(" "), MASTER), "name");
        var sparkles = "✨".repeat(60);
        assertThat(categories.rename(RENT, new RenameCategory(sparkles), MASTER).name())
                .isEqualTo(sparkles);
    }

    @Test
    void readAndWriteRights() {
        assertThat(categories.list(EntryKind.EXPENSE, DEVELOPER)).hasSize(6);

        assertDenied(() -> categories.create(new CreateCategory(EntryKind.EXPENSE, "Курсы"), DEVELOPER));
        assertDenied(() -> categories.rename(RENT, new RenameCategory("Курсы"), DEVELOPER));
        assertDenied(() -> categories.hide(RENT, DEVELOPER));
        assertDenied(() -> categories.show(RENT, DEVELOPER));
        assertDenied(() -> categories.reorder(EntryKind.EXPENSE, ids(EntryKind.EXPENSE), DEVELOPER));
        assertDenied(() -> categories.list(EntryKind.EXPENSE, CLIENT));
        assertDenied(() -> categories.create(new CreateCategory(EntryKind.EXPENSE, "Курсы"), CLIENT));
        assertThat(categories.list(EntryKind.EXPENSE, MASTER))
                .extracting(CategoryView::name)
                .containsExactly("Материалы", "Аренда", "Обучение", "Инструменты", "Реклама", "Прочее");
    }

    private List<UUID> ids(EntryKind kind) {
        return categories.list(kind, MASTER).stream().map(CategoryView::id).toList();
    }

    private static void assertNameTaken(ThrowingCallable call, String name) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        CategoryNameTakenException.class,
                        e -> assertThat(e.params()).containsEntry("name", name));
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }
}
