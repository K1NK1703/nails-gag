package gag.nails.api.internal.finance;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.finance.model.EntryKind;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Категории финансов (спецификация финансов, §5): список вида, создание, переименование, скрытие и показ, порядок.
 * Чтение — по уровню чтения раздела «Финансы», изменения — по уровню изменения; права и правила проверяет модуль, здесь
 * — что адреса доходят до нужного вызова, вид в адресе — через дефис, ответы не кэшируются. Категории общие для всей
 * базы: тест заводит свои и проверяет только их.
 */
@ApiTest
class FinanceCategoriesApiTest extends FinanceApiTestSupport {

    @Test
    void masterCreatesRenamesHidesAndShowsCategory() {
        var master = signInMaster();
        var name = uniqueCategoryName();

        var created = json(
                post(master, Map.of("kind", "EXPENSE", "name", name), "/api/finance/categories"), HttpStatus.CREATED);
        var id = UUID.fromString(field(created, "id").asString());
        assertThat(field(created, "kind").asString()).isEqualTo("EXPENSE");
        assertThat(field(created, "name").asString()).isEqualTo(name);
        assertThat(field(created, "hidden").asBoolean()).isFalse();

        var renamed = uniqueCategoryName();
        var afterRename = json(patch(master, Map.of("name", renamed), "/api/finance/categories/{id}", id));
        assertThat(field(afterRename, "name").asString()).isEqualTo(renamed);

        action(master, "/api/finance/categories/{id}/hide", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(field(listed(master, "expense", id), "hidden").asBoolean()).isTrue();
        action(master, "/api/finance/categories/{id}/show", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(field(listed(master, "expense", id), "hidden").asBoolean()).isFalse();
        assertThat(field(listed(master, "expense", id), "name").asString()).isEqualTo(renamed);
    }

    @Test
    void listIsOfOneKindAndNotStored() {
        var master = signInMaster();
        var income = category(actor(master), EntryKind.INCOME);
        var expense = category(actor(master), EntryKind.EXPENSE);

        var incomes = noStore(get(master, "/api/finance/categories?entryKind=income"));

        assertThat(ids(incomes)).contains(income.id()).doesNotContain(expense.id());
        assertThat(incomes.values())
                .allSatisfy(category ->
                        assertThat(field(category, "kind").asString()).isEqualTo("INCOME"));
    }

    @Test
    void masterReordersWholeListOfKind() {
        var master = signInMaster();
        var added = category(actor(master), EntryKind.INCOME);
        var order = new ArrayList<>(ids(json(get(master, "/api/finance/categories?entryKind=income"))));
        order.remove(added.id());
        order.addFirst(added.id());

        put(master, Map.of("kind", "INCOME", "orderedIds", order), "/api/finance/categories/order")
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(ids(json(get(master, "/api/finance/categories?entryKind=income"))))
                .containsExactlyElementsOf(order);
    }

    @Test
    void incompleteOrderIsBadRequest() {
        var master = signInMaster();
        var all = ids(json(get(master, "/api/finance/categories?entryKind=expense")));

        expectInvalid(
                put(
                        master,
                        Map.of("kind", "EXPENSE", "orderedIds", all.subList(1, all.size())),
                        "/api/finance/categories/order"),
                "orderedIds");
    }

    @Test
    void takenNameIsConflict() {
        var master = signInMaster();
        var existing = category(actor(master), EntryKind.EXPENSE);

        var problem = json(
                post(master, Map.of("kind", "EXPENSE", "name", existing.name()), "/api/finance/categories"),
                HttpStatus.CONFLICT);

        assertThat(field(problem, "code").asString()).isEqualTo("finance.category_name_taken");
        assertThat(field(field(problem, "params"), "name").asString()).isEqualTo(existing.name());
    }

    @Test
    void unknownCategoryIsNotFound() {
        var master = signInMaster();
        var unknown = UUID.randomUUID();

        var problem = json(
                patch(master, Map.of("name", uniqueCategoryName()), "/api/finance/categories/{id}", unknown),
                HttpStatus.NOT_FOUND);

        assertThat(field(problem, "code").asString()).isEqualTo("finance.category_not_found");
        assertThat(field(field(problem, "params"), "categoryId").asString()).isEqualTo(unknown.toString());
        expectProblem(
                action(master, "/api/finance/categories/{id}/hide", unknown),
                HttpStatus.NOT_FOUND,
                "finance.category_not_found");
    }

    @Test
    void blankOrLongNameIsBadRequest() {
        var master = signInMaster();

        expectInvalid(post(master, Map.of("kind", "INCOME", "name", " "), "/api/finance/categories"), "name");
        expectInvalid(
                post(master, Map.of("kind", "INCOME", "name", "я".repeat(61)), "/api/finance/categories"), "name");
        expectInvalid(post(master, Map.of("name", uniqueCategoryName()), "/api/finance/categories"), "kind");
    }

    /** Вид в адресе — только через дефис: имя константы Java и неизвестное значение — неверный параметр. */
    @Test
    void kindInAddressIsKebabCase() {
        var master = signInMaster();

        for (var kind : new String[] {"INCOME", "Income", "incomes"}) {
            expectInvalid(get(master, "/api/finance/categories?entryKind={kind}", kind), "entryKind");
        }
        expectInvalid(get(master, "/api/finance/categories"), "entryKind");
    }

    @Test
    void developerReadsButCannotChange() {
        var developer = signInDeveloper();

        noStore(get(developer, "/api/finance/categories?entryKind=expense"));
        expectProblem(
                post(developer, Map.of("kind", "EXPENSE", "name", uniqueCategoryName()), "/api/finance/categories"),
                HttpStatus.FORBIDDEN,
                "common.access_denied");
    }

    /** Клиент не отличает закрытый раздел от несуществующего адреса. */
    @Test
    void clientGetsNotFound() {
        var customer = signInClient();

        expectProblem(
                get(customer, "/api/finance/categories?entryKind=income"), HttpStatus.NOT_FOUND, "common.not_found");
        expectProblem(
                post(customer, Map.of("kind", "INCOME", "name", uniqueCategoryName()), "/api/finance/categories"),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void anonymousGetsUnauthenticated() {
        expectProblem(
                client.get().uri("/api/finance/categories?entryKind=income"),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    /** Категория из списка вида, прочитанного по адресу; её отсутствие — провал теста. */
    private JsonNode listed(Session session, String kind, UUID categoryId) {
        var found = json(get(session, "/api/finance/categories?entryKind={kind}", kind)).values().stream()
                .filter(category -> field(category, "id").asString().equals(categoryId.toString()))
                .toList();
        assertThat(found).as("category " + categoryId).hasSize(1);
        return found.getFirst();
    }
}
