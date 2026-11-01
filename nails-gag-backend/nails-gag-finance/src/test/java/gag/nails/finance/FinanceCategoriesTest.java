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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

@FinanceModuleTest
class FinanceCategoriesTest extends FinanceTestSupport {

    @Test
    void startsWithDefaultCategories() {
        var expenses = categories.list(EntryKind.EXPENSE, MASTER);
        var incomes = categories.list(EntryKind.INCOME, MASTER);

        // Сверяется стартовый набор по известным id, а не весь список: в базе могут быть и другие категории.
        assertThat(expenses).extracting(CategoryView::id).containsSubsequence(DEFAULT_EXPENSES);
        assertThat(incomes).extracting(CategoryView::id).containsSubsequence(DEFAULT_INCOMES);
        assertThat(defaults(expenses, DEFAULT_EXPENSES))
                .extracting(CategoryView::name)
                .containsExactly("Материалы", "Аренда", "Обучение", "Инструменты", "Реклама", "Прочее");
        assertThat(defaults(incomes, DEFAULT_INCOMES))
                .extracting(CategoryView::name)
                .containsExactly("Чаевые", "Продажа товаров", "Прочее");
        assertThat(defaults(expenses, DEFAULT_EXPENSES)).allSatisfy(category -> {
            assertThat(category.kind()).isEqualTo(EntryKind.EXPENSE);
            assertThat(category.hidden()).isFalse();
        });
        assertThat(defaults(incomes, DEFAULT_INCOMES)).allSatisfy(category -> {
            assertThat(category.kind()).isEqualTo(EntryKind.INCOME);
            assertThat(category.hidden()).isFalse();
        });
    }

    @Test
    void createAppendsToTheEndOfItsKind() {
        var created = category(EntryKind.EXPENSE, "  Курсы  ");

        assertThat(created)
                .isEqualTo(new CategoryView(created.id(), EntryKind.EXPENSE, "Курсы", false, created.position()));
        var expenses = categories.list(EntryKind.EXPENSE, MASTER);
        assertThat(expenses).last().isEqualTo(created);
        assertThat(expenses.subList(0, expenses.size() - 1))
                .extracting(CategoryView::position)
                .allMatch(position -> position < created.position());
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
        var rentPosition = category(RENT).position();
        var renamed = categories.rename(RENT, new RenameCategory("Аренда кабинета"), MASTER);

        assertThat(renamed)
                .isEqualTo(new CategoryView(RENT, EntryKind.EXPENSE, "Аренда кабинета", false, rentPosition));
        var courses = category(EntryKind.EXPENSE, "Курсы");
        var gifts = category(EntryKind.EXPENSE, "Подарки");
        var reversed = ids(EntryKind.EXPENSE).reversed();

        categories.reorder(EntryKind.EXPENSE, reversed, MASTER);

        var after = categories.list(EntryKind.EXPENSE, MASTER);
        assertThat(after).extracting(CategoryView::id).containsExactlyElementsOf(reversed);
        assertThat(after).extracting(CategoryView::id).containsSubsequence(gifts.id(), courses.id(), RENT, MATERIALS);
        assertThat(after)
                .extracting(CategoryView::position)
                .containsExactlyElementsOf(
                        IntStream.range(0, after.size()).boxed().toList());
        assertThat(category(RENT).name()).isEqualTo("Аренда кабинета");
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
        var rent = category(RENT);
        categories.hide(RENT, MASTER);
        categories.hide(RENT, MASTER);

        assertThat(category(RENT))
                .isEqualTo(new CategoryView(RENT, EntryKind.EXPENSE, "Аренда", true, rent.position()));

        categories.show(RENT, MASTER);
        categories.show(RENT, MASTER);

        assertThat(category(RENT)).isEqualTo(rent);
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
        var before = categories.list(EntryKind.EXPENSE, MASTER);
        assertThat(categories.list(EntryKind.EXPENSE, DEVELOPER)).isEqualTo(before);

        assertDenied(() -> categories.create(new CreateCategory(EntryKind.EXPENSE, "Курсы"), DEVELOPER));
        assertDenied(() -> categories.rename(RENT, new RenameCategory("Курсы"), DEVELOPER));
        assertDenied(() -> categories.hide(RENT, DEVELOPER));
        assertDenied(() -> categories.show(RENT, DEVELOPER));
        assertDenied(() -> categories.reorder(EntryKind.EXPENSE, ids(EntryKind.EXPENSE), DEVELOPER));
        assertDenied(() -> categories.list(EntryKind.EXPENSE, CLIENT));
        assertDenied(() -> categories.create(new CreateCategory(EntryKind.EXPENSE, "Курсы"), CLIENT));
        assertThat(categories.list(EntryKind.EXPENSE, MASTER)).isEqualTo(before);
    }

    /** Категория из списка её вида — так видна её позиция и пометка скрытой. */
    private CategoryView category(UUID categoryId) {
        return Stream.of(EntryKind.values())
                .flatMap(kind -> categories.list(kind, MASTER).stream())
                .filter(category -> category.id().equals(categoryId))
                .findFirst()
                .orElseThrow();
    }

    /** Стартовые категории из списка в его порядке. */
    private static List<CategoryView> defaults(List<CategoryView> list, List<UUID> defaultIds) {
        return list.stream()
                .filter(category -> defaultIds.contains(category.id()))
                .toList();
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
