package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.AccessDeniedException;
import gag.nails.finance.command.CreateRecurring;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.error.RecurringNotFoundException;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.RecurringView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** Правила шаблонов; записи по шаблонам — {@link RecurringEntriesTest}. */
@FinanceModuleTest
class FinanceRecurringTest extends RecurringTestSupport {

    private static final LocalDate NEXT_MONTH = TODAY.plusMonths(1);

    @Test
    void createListPauseResumeDelete() {
        var created = recurring.create(
                new CreateRecurring(RENT, new BigDecimal("30000"), "  Кабинет ", 5, NEXT_MONTH, null), MASTER);

        assertThat(created)
                .isEqualTo(new RecurringView(
                        created.id(),
                        EntryKind.EXPENSE,
                        RENT,
                        "Аренда",
                        new BigDecimal("30000.00"),
                        "Кабинет",
                        5,
                        NEXT_MONTH,
                        null,
                        false));
        assertThat(view(created.id())).isEqualTo(created);

        recurring.pause(created.id(), MASTER);
        recurring.pause(created.id(), MASTER);
        assertThat(view(created.id()).paused()).isTrue();

        recurring.resume(created.id(), MASTER);
        recurring.resume(created.id(), MASTER);
        assertThat(view(created.id())).isEqualTo(created);

        recurring.delete(created.id(), MASTER);
        assertThat(recurring.list(MASTER)).extracting(RecurringView::id).doesNotContain(created.id());
        assertNotFound(() -> recurring.delete(created.id(), MASTER));
        assertNotFound(() -> recurring.pause(created.id(), MASTER));
        assertNotFound(() -> recurring.resume(created.id(), MASTER));
        assertNotFound(() -> recurring.update(created.id(), change(created, "1", 5, NEXT_MONTH), MASTER));
    }

    @Test
    void listIsOrderedByDayOfMonth() {
        var late = template(MATERIALS, "100", 28, NEXT_MONTH, null);
        var early = template(TIPS, "100", 1, NEXT_MONTH, null);

        assertThat(recurring.list(DEVELOPER)).extracting(RecurringView::id).containsSubsequence(early.id(), late.id());
    }

    @Test
    void updateChangesFieldsButNotKind() {
        var rent = template(RENT, "30000", 5, NEXT_MONTH, null);

        var updated = recurring.update(
                rent.id(),
                new UpdateRecurring(
                        MATERIALS, new BigDecimal("1500.5"), " Гель ", 10, NEXT_MONTH, NEXT_MONTH.plusMonths(6)),
                MASTER);

        assertThat(updated)
                .isEqualTo(new RecurringView(
                        rent.id(),
                        EntryKind.EXPENSE,
                        MATERIALS,
                        "Материалы",
                        new BigDecimal("1500.50"),
                        "Гель",
                        10,
                        NEXT_MONTH,
                        NEXT_MONTH.plusMonths(6),
                        false));
        assertThat(view(rent.id())).isEqualTo(updated);
        assertInvalid(() -> recurring.update(rent.id(), change(rent, TIPS), MASTER), "categoryId");
    }

    @Test
    void dayOfMonthBetween1And31() {
        assertInvalid(() -> template(RENT, "100", 0, NEXT_MONTH, null), "dayOfMonth");
        assertInvalid(() -> template(RENT, "100", 32, NEXT_MONTH, null), "dayOfMonth");
        assertThat(template(RENT, "100", 1, NEXT_MONTH, null).dayOfMonth()).isEqualTo(1);
        var last = template(RENT, "100", 31, NEXT_MONTH, null);
        assertThat(last.dayOfMonth()).isEqualTo(31);
        assertInvalid(() -> recurring.update(last.id(), change(last, "100", 0, NEXT_MONTH), MASTER), "dayOfMonth");
        assertInvalid(() -> recurring.update(last.id(), change(last, "100", 32, NEXT_MONTH), MASTER), "dayOfMonth");
    }

    @Test
    void endsOnNotBeforeStartsOn() {
        assertInvalid(() -> template(RENT, "100", 5, NEXT_MONTH, NEXT_MONTH.minusDays(1)), "endsOn");
        var oneDay = template(RENT, "100", 5, NEXT_MONTH, NEXT_MONTH);
        assertThat(oneDay.endsOn()).isEqualTo(NEXT_MONTH);
        assertInvalid(
                () -> recurring.update(
                        oneDay.id(),
                        new UpdateRecurring(RENT, BigDecimal.TEN, null, 5, NEXT_MONTH, NEXT_MONTH.minusDays(1)),
                        MASTER),
                "endsOn");

        // Дни шаблона — в тех же пределах, что дни записей.
        assertInvalid(() -> template(RENT, "100", 5, LocalDate.of(1999, 12, 31), null), "startsOn");
        assertInvalid(() -> template(RENT, "100", 5, TODAY.plusDays(367), null), "startsOn");
        assertInvalid(() -> template(RENT, "100", 5, NEXT_MONTH, TODAY.plusDays(367)), "endsOn");
        assertInvalid(() -> template(RENT, "100", 5, NEXT_MONTH, LocalDate.MAX), "endsOn");
    }

    @Test
    void amountAndCommentAsInEntries() {
        assertInvalid(() -> template(RENT, "0", 5, NEXT_MONTH, null), "amount");
        assertInvalid(() -> template(RENT, "1.005", 5, NEXT_MONTH, null), "amount");
        assertInvalid(() -> template(RENT, "10000000000", 5, NEXT_MONTH, null), "amount");
        assertInvalid(
                () -> recurring.create(
                        new CreateRecurring(RENT, BigDecimal.TEN, "x".repeat(501), 5, NEXT_MONTH, null), MASTER),
                "comment");
        assertThat(recurring
                        .create(new CreateRecurring(RENT, BigDecimal.TEN, "   ", 5, NEXT_MONTH, null), MASTER)
                        .comment())
                .isNull();
        assertInvalid(
                () -> recurring.create(new CreateRecurring(RENT, null, null, 5, NEXT_MONTH, null), MASTER), "amount");
        assertInvalid(
                () -> recurring.create(new CreateRecurring(RENT, BigDecimal.TEN, null, 5, null, null), MASTER),
                "startsOn");
    }

    @Test
    void hiddenCategoryCannotBeChosenButCanBeKept() {
        var rent = template(RENT, "30000", 5, NEXT_MONTH, null);
        var materials = template(MATERIALS, "100", 5, NEXT_MONTH, null);
        categories.hide(RENT, MASTER);

        assertInvalid(() -> template(RENT, "30000", 5, NEXT_MONTH, null), "categoryId");
        assertInvalid(() -> recurring.update(materials.id(), change(materials, RENT), MASTER), "categoryId");
        assertThat(recurring
                        .update(rent.id(), change(rent, "32000", 5, NEXT_MONTH), MASTER)
                        .amount())
                .isEqualTo(new BigDecimal("32000.00"));
    }

    @Test
    void unknownCategoryIsNotFound() {
        var unknown = UUID.randomUUID();
        var rent = template(RENT, "30000", 5, NEXT_MONTH, null);

        assertThatThrownBy(() -> template(unknown, "100", 5, NEXT_MONTH, null))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> recurring.update(rent.id(), change(rent, unknown), MASTER))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void readAndWriteRights() {
        var rent = template(RENT, "30000", 5, NEXT_MONTH, null);

        assertThat(recurring.list(DEVELOPER)).contains(rent);
        assertDenied(() ->
                recurring.create(new CreateRecurring(RENT, BigDecimal.TEN, null, 5, NEXT_MONTH, null), DEVELOPER));
        assertDenied(() -> recurring.update(rent.id(), change(rent, "1", 5, NEXT_MONTH), DEVELOPER));
        assertDenied(() -> recurring.pause(rent.id(), DEVELOPER));
        assertDenied(() -> recurring.resume(rent.id(), DEVELOPER));
        assertDenied(() -> recurring.delete(rent.id(), DEVELOPER));
        assertDenied(() -> recurring.list(CLIENT));
        assertDenied(() -> recurring.pause(rent.id(), CLIENT));
        assertThat(view(rent.id())).isEqualTo(rent);
    }

    private RecurringView view(UUID recurringId) {
        return recurring.list(MASTER).stream()
                .filter(template -> template.id().equals(recurringId))
                .findFirst()
                .orElseThrow();
    }

    private static UpdateRecurring change(RecurringView template, UUID categoryId) {
        return new UpdateRecurring(
                categoryId,
                template.amount(),
                template.comment(),
                template.dayOfMonth(),
                template.startsOn(),
                template.endsOn());
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(RecurringNotFoundException.class);
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }
}
