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
    static final UUID TIPS = UUID.fromString("019a2f00-0000-7000-8000-000000000101");

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
