package gag.nails.api.internal.finance;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.common.paging.Paging;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Ручные доходы и расходы (спецификация финансов, §5): внесение, правка, удаление, история, список периода
 * постранично, быстрый ввод. Права и правила проверяет модуль, здесь — что адреса доходят до нужного вызова и отдают
 * его ответ. Записи теста — на его собственных днях ({@link #entryDay()}) и в его категориях.
 */
@ApiTest
class FinanceEntriesApiTest extends FinanceApiTestSupport {

    @Test
    void masterAddsChangesAndDeletesEntry() {
        var master = signInMaster();
        var category = category(actor(master), EntryKind.EXPENSE);
        var day = entryDay();

        var added = noStore(
                post(master, entryBody(category.id(), "1500.50", day.toString(), "Лаки"), "/api/finance/entries"),
                HttpStatus.CREATED);
        var id = UUID.fromString(field(added, "id").asString());
        assertThat(field(added, "kind").asString()).isEqualTo("EXPENSE");
        assertThat(field(added, "categoryId").asString())
                .isEqualTo(category.id().toString());
        assertThat(field(added, "categoryName").asString()).isEqualTo(category.name());
        assertThat(field(added, "amount").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(field(added, "occurredOn").asString()).isEqualTo(day.toString());
        assertThat(field(added, "comment").asString()).isEqualTo("Лаки");
        assertThat(field(added, "recurringId").isNull()).isTrue();

        var read = noStore(get(master, "/api/finance/entries/{id}", id));
        assertThat(field(read, "id").asString()).isEqualTo(id.toString());

        var changed = noStore(
                patch(master, entryBody(category.id(), "1200", day.toString(), null), "/api/finance/entries/{id}", id));
        assertThat(field(changed, "amount").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(field(changed, "comment").isNull()).isTrue();

        delete(master, "/api/finance/entries/{id}", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        var problem = json(get(master, "/api/finance/entries/{id}", id), HttpStatus.NOT_FOUND);
        assertThat(field(problem, "code").asString()).isEqualTo("finance.entry_not_found");
        assertThat(field(field(problem, "params"), "entryId").asString()).isEqualTo(id.toString());

        // История удалённой записи остаётся: создание, правка, удаление — со снимками «было» и «стало».
        var history = noStore(get(master, "/api/finance/entries/{id}/history", id));
        assertThat(history.values())
                .extracting(change -> field(change, "action").asString())
                .containsExactly("CREATE", "UPDATE", "DELETE");
        var update = history.path(1);
        assertThat(field(field(update, "before"), "amount").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(field(field(update, "after"), "amount").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(field(history.path(2), "after").isNull()).isTrue();
        assertThat(field(update, "accountId").asString())
                .isEqualTo(master.accountId().toString());
    }

    @Test
    void listIsPagedAndFiltered() {
        var master = signInMaster();
        var expense = category(actor(master), EntryKind.EXPENSE);
        var day = entryDay();
        for (var amount : new String[] {"100", "200", "300"}) {
            entry(actor(master), expense.id(), amount, day);
        }
        var expected = entries.list(new EntryFilter(day, day, null, expense.id()), new Paging(0, 50), actor(master));
        assertThat(expected.total()).isEqualTo(3);

        var first = noStore(get(
                master,
                "/api/finance/entries?from={day}&to={day}&categoryId={id}&page=0&size=2",
                day,
                day,
                expense.id()));
        var second = json(get(
                master,
                "/api/finance/entries?from={day}&to={day}&categoryId={id}&page=1&size=2",
                day,
                day,
                expense.id()));

        assertThat(field(first, "total").asLong()).isEqualTo(3);
        assertThat(field(first, "page").asInt()).isZero();
        assertThat(field(first, "size").asInt()).isEqualTo(2);
        assertThat(field(second, "page").asInt()).isEqualTo(1);
        assertThat(ids(field(first, "items")))
                .containsExactlyElementsOf(expected.items().subList(0, 2).stream()
                        .map(EntryView::id)
                        .toList());
        assertThat(ids(field(second, "items")))
                .containsExactly(expected.items().get(2).id());

        var incomes = json(get(
                master,
                "/api/finance/entries?from={day}&to={day}&categoryId={id}&entryKind=income",
                day,
                day,
                expense.id()));
        assertThat(field(incomes, "total").asLong()).isZero();
        assertThat(field(incomes, "size").asInt()).isEqualTo(50);
        var expenses = json(get(master, "/api/finance/entries?from={day}&to={day}&entryKind=expense", day, day));
        assertThat(ids(field(expenses, "items")))
                .containsExactlyInAnyOrderElementsOf(
                        expected.items().stream().map(EntryView::id).toList());
    }

    @Test
    void recentCombosOfferLastEntries() {
        var master = signInMaster();
        var hidden = category(actor(master), EntryKind.INCOME);
        entry(actor(master), hidden.id(), "777.70", entryDay());
        categories.hide(hidden.id(), actor(master));
        var visible = category(actor(master), EntryKind.INCOME);
        entry(actor(master), visible.id(), "555.50", entryDay());
        var own = pair(visible.id(), visible.name(), new BigDecimal("555.50"));

        // Пары общие для всей базы: ответ сверяется с ответом модуля, прочитанным сразу перед запросом, — чтение пар
        // само записей не добавляет.
        var expected = entries.recentCombos(EntryKind.INCOME, actor(master));
        var combos = noStore(get(master, "/api/finance/entries/recent?entryKind=income"));

        var offered = combos.values().stream()
                .map(combo -> pair(
                        UUID.fromString(field(combo, "categoryId").asString()),
                        field(combo, "categoryName").asString(),
                        field(combo, "amount").decimalValue()))
                .toList();
        var expectedPairs = expected.stream()
                .map(combo -> pair(combo.categoryId(), combo.categoryName(), combo.amount()))
                .toList();
        assertThat(offered).containsExactlyElementsOf(expectedPairs);
        // Своя видимая пара — в ответе, когда модуль относит её к последним пяти: чужие записи с более поздним моментом
        // внесения могут её вытеснить, и это не ошибка адреса.
        if (expectedPairs.contains(own)) {
            assertThat(offered).contains(own);
        }
        // Своя пара со скрытой категорией не предлагается, сколько бы пар ни было в базе.
        assertThat(combos.values())
                .noneSatisfy(combo -> assertThat(field(combo, "categoryId").asString())
                        .isEqualTo(hidden.id().toString()));
    }

    @Test
    void unknownCategoryIsNotFound() {
        var master = signInMaster();
        var unknown = UUID.randomUUID();

        var problem = json(
                post(master, entryBody(unknown, "100", entryDay().toString(), null), "/api/finance/entries"),
                HttpStatus.NOT_FOUND);

        assertThat(field(problem, "code").asString()).isEqualTo("finance.category_not_found");
        assertThat(field(field(problem, "params"), "categoryId").asString()).isEqualTo(unknown.toString());
    }

    @Test
    void invalidFieldsAreBadRequest() {
        var master = signInMaster();
        var category = category(actor(master), EntryKind.EXPENSE);
        var day = entryDay().toString();

        expectInvalid(post(master, entryBody(category.id(), "0", day, null), "/api/finance/entries"), "amount");
        expectInvalid(post(master, entryBody(category.id(), "10.001", day, null), "/api/finance/entries"), "amount");
        expectInvalid(
                post(master, entryBody(category.id(), "10", "1999-12-31", null), "/api/finance/entries"), "occurredOn");
        expectInvalid(
                post(master, entryBody(category.id(), "10", day, "к".repeat(501)), "/api/finance/entries"), "comment");
        var withoutAmount = entryBody(category.id(), "10", day, null);
        withoutAmount.remove("amount");
        expectInvalid(post(master, withoutAmount, "/api/finance/entries"), "amount");
    }

    @Test
    void periodAndKindAreChecked() {
        var master = signInMaster();

        expectInvalid(get(master, "/api/finance/entries?from=2026-03-02&to=2026-03-01"), "to");
        expectInvalid(get(master, "/api/finance/entries?from=2025-01-01&to=2026-01-02"), "to");
        expectInvalid(get(master, "/api/finance/entries?to=2026-03-01"), "from");
        expectInvalid(get(master, "/api/finance/entries?from=2026-03-01&to=2026-03-01&entryKind=EXPENSE"), "entryKind");
        expectInvalid(get(master, "/api/finance/entries/recent?entryKind=INCOME"), "entryKind");
        expectInvalid(get(master, "/api/finance/entries?from=2026-03-01&to=2026-03-01&size=201"), "size");
    }

    @Test
    void developerReadsButCannotChange() {
        var developer = signInDeveloper();
        var master = actor(signInMaster());
        var category = category(master, EntryKind.EXPENSE);
        var entry = entry(master, category.id(), "50", entryDay());

        noStore(get(developer, "/api/finance/entries/{id}", entry.id()));
        noStore(get(developer, "/api/finance/entries?from={day}&to={day}", entry.occurredOn(), entry.occurredOn()));
        expectProblem(
                patch(
                        developer,
                        entryBody(category.id(), "60", entry.occurredOn().toString(), null),
                        "/api/finance/entries/{id}",
                        entry.id()),
                HttpStatus.FORBIDDEN,
                "common.access_denied");
        expectProblem(
                delete(developer, "/api/finance/entries/{id}", entry.id()),
                HttpStatus.FORBIDDEN,
                "common.access_denied");
    }

    @Test
    void clientGetsNotFound() {
        var customer = signInClient();
        var master = actor(signInMaster());
        var entry = entry(master, category(master, EntryKind.INCOME).id(), "50", entryDay());

        expectProblem(get(customer, "/api/finance/entries/{id}", entry.id()), HttpStatus.NOT_FOUND, "common.not_found");
        expectProblem(
                post(
                        customer,
                        entryBody(entry.categoryId(), "50", entry.occurredOn().toString(), null),
                        "/api/finance/entries"),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        // Несуществующая запись — тот же ответ, что и закрытая.
        expectProblem(
                get(customer, "/api/finance/entries/{id}", UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void anonymousGetsUnauthenticated() {
        expectProblem(
                client.get().uri("/api/finance/entries/recent?entryKind=expense"),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    /** Пара быстрого ввода строкой для сравнения; сумма — без незначащих нулей (JSON их не сохраняет). */
    private static String pair(UUID categoryId, String categoryName, BigDecimal amount) {
        return categoryId + " " + categoryName + " "
                + amount.stripTrailingZeros().toPlainString();
    }

    /** Тело записи; изменяемое — тест может убрать поле. */
    private static Map<String, @Nullable Object> entryBody(
            UUID categoryId, String amount, String occurredOn, @Nullable String comment) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("categoryId", categoryId);
        body.put("amount", new BigDecimal(amount));
        body.put("occurredOn", occurredOn);
        body.put("comment", comment);
        return body;
    }
}
