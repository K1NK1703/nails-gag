package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Подготовка данных через публичные API финансов. Мастер действует со своим аккаунтом — он попадает в журнал
 * изменений. Часы стоят на четверге 1 октября 2026 (09:00 в поясе салона).
 */
abstract class FinanceTestSupport {

    static final UUID MASTER_ACCOUNT = UUID.fromString("0198b2f0-0000-7000-8000-000000000001");

    /** Мастер со своим аккаунтом: изменение «Финансов». */
    static final Actor MASTER = Actor.of(MASTER_ACCOUNT, null, Actor.masterAccess());

    /** Разработчик: чтение «Финансов». */
    static final Actor DEVELOPER = Actor.developer();

    static final Actor CLIENT = Actor.client(UUID.fromString("0198b2f0-0000-7000-8000-000000000099"));

    /** Сегодня в поясе салона по часам тестов. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    // Стартовые категории из миграции.
    static final UUID MATERIALS = UUID.fromString("019a2f00-0000-7000-8000-000000000001");
    static final UUID RENT = UUID.fromString("019a2f00-0000-7000-8000-000000000002");
    static final UUID LEARNING = UUID.fromString("019a2f00-0000-7000-8000-000000000003");
    static final UUID TOOLS = UUID.fromString("019a2f00-0000-7000-8000-000000000004");
    static final UUID ADS = UUID.fromString("019a2f00-0000-7000-8000-000000000005");
    static final UUID OTHER_EXPENSES = UUID.fromString("019a2f00-0000-7000-8000-000000000006");
    static final UUID TIPS = UUID.fromString("019a2f00-0000-7000-8000-000000000101");
    static final UUID SALES = UUID.fromString("019a2f00-0000-7000-8000-000000000102");
    static final UUID OTHER_INCOME = UUID.fromString("019a2f00-0000-7000-8000-000000000103");

    /** Стартовые расходы в порядке миграции. */
    static final List<UUID> DEFAULT_EXPENSES = List.of(MATERIALS, RENT, LEARNING, TOOLS, ADS, OTHER_EXPENSES);

    /** Стартовые доходы в порядке миграции. */
    static final List<UUID> DEFAULT_INCOMES = List.of(TIPS, SALES, OTHER_INCOME);

    @Autowired
    FinanceCategories categories;

    @Autowired
    FinanceEntries entries;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    CategoryView category(EntryKind kind, String name) {
        return categories.create(new CreateCategory(kind, name), MASTER);
    }

    EntryView add(UUID categoryId, String amount, LocalDate occurredOn, @Nullable String comment) {
        return entries.add(new AddEntry(categoryId, new BigDecimal(amount), occurredOn, comment), MASTER);
    }

    EntryView add(UUID categoryId, String amount) {
        return add(categoryId, amount, TODAY, null);
    }

    /** Следующее действие — на минуту позже: моменты различаются, порядок по ним однозначен. */
    void tick() {
        clock.advance(Duration.ofMinutes(1));
    }

    static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo(field));
    }
}
