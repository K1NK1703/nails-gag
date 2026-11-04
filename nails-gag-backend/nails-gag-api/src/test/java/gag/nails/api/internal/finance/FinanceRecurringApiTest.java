package gag.nails.api.internal.finance;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.common.paging.Paging;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Повторяющиеся записи (спецификация финансов, §5): список, создание, правка, пауза и снятие паузы, удаление. Правила
 * шаблонов проверяет модуль, здесь — что адреса доходят до нужного вызова. Шаблоны теста начинаются в будущем, кроме
 * явно проверяемого, и удаляются в конце: записи по ним другим тестам не мешают.
 */
@ApiTest
class FinanceRecurringApiTest extends FinanceApiTestSupport {

    @Test
    void masterCreatesChangesPausesResumesAndDeletesTemplate() {
        var master = signInMaster();
        var category = category(actor(master), EntryKind.EXPENSE);
        var startsOn = LocalDate.now(clock).plusDays(30);

        var created = json(
                post(master, recurringBody(category.id(), "25000", 5, startsOn, null), "/api/finance/recurring"),
                HttpStatus.CREATED);
        var id = UUID.fromString(field(created, "id").asString());
        assertThat(field(created, "kind").asString()).isEqualTo("EXPENSE");
        assertThat(field(created, "categoryName").asString()).isEqualTo(category.name());
        assertThat(field(created, "amount").decimalValue()).isEqualByComparingTo("25000.00");
        assertThat(field(created, "dayOfMonth").asInt()).isEqualTo(5);
        assertThat(field(created, "startsOn").asString()).isEqualTo(startsOn.toString());
        assertThat(field(created, "endsOn").isNull()).isTrue();
        assertThat(field(created, "paused").asBoolean()).isFalse();

        var endsOn = startsOn.plusDays(200);
        var changed = json(patch(
                master,
                recurringBody(category.id(), "27000", 10, startsOn, endsOn),
                "/api/finance/recurring/{id}",
                id));
        assertThat(field(changed, "amount").decimalValue()).isEqualByComparingTo("27000.00");
        assertThat(field(changed, "dayOfMonth").asInt()).isEqualTo(10);
        assertThat(field(changed, "endsOn").asString()).isEqualTo(endsOn.toString());

        action(master, "/api/finance/recurring/{id}/pause", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(field(listed(master, id), "paused").asBoolean()).isTrue();
        action(master, "/api/finance/recurring/{id}/resume", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(field(listed(master, id), "paused").asBoolean()).isFalse();

        delete(master, "/api/finance/recurring/{id}", id)
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(ids(noStore(get(master, "/api/finance/recurring")))).doesNotContain(id);
        var problem = json(action(master, "/api/finance/recurring/{id}/pause", id), HttpStatus.NOT_FOUND);
        assertThat(field(problem, "code").asString()).isEqualTo("finance.recurring_not_found");
        assertThat(field(field(problem, "params"), "recurringId").asString()).isEqualTo(id.toString());
    }

    /** Шаблон, начатый в прошлом, сразу создаёт записи за наступившие дни — не дожидаясь ночной задачи. */
    @Test
    void templateStartedInPastCreatesEntriesAtOnce() {
        var master = signInMaster();
        var category = category(actor(master), EntryKind.INCOME);
        var day = entryDay();

        var created = json(
                post(
                        master,
                        recurringBody(category.id(), "100", day.getDayOfMonth(), day, day),
                        "/api/finance/recurring"),
                HttpStatus.CREATED);

        var made = entries.list(new EntryFilter(day, day, null, category.id()), new Paging(0, 50), actor(master));
        assertThat(made.items()).singleElement().satisfies(entry -> {
            assertThat(entry.recurringId()).hasToString(field(created, "id").asString());
            assertThat(entry.amount()).isEqualByComparingTo("100.00");
        });
        delete(master, "/api/finance/recurring/{id}", field(created, "id").asString())
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void invalidFieldsAreBadRequest() {
        var master = signInMaster();
        var category = category(actor(master), EntryKind.EXPENSE);
        var startsOn = LocalDate.now(clock).plusDays(30);

        expectInvalid(
                post(master, recurringBody(category.id(), "100", 32, startsOn, null), "/api/finance/recurring"),
                "dayOfMonth");
        expectInvalid(
                post(
                        master,
                        recurringBody(category.id(), "100", 1, startsOn, startsOn.minusDays(1)),
                        "/api/finance/recurring"),
                "endsOn");
        var unknown = UUID.randomUUID();
        var problem = json(
                post(master, recurringBody(unknown, "100", 1, startsOn, null), "/api/finance/recurring"),
                HttpStatus.NOT_FOUND);
        assertThat(field(problem, "code").asString()).isEqualTo("finance.category_not_found");
    }

    @Test
    void developerReadsButCannotChange() {
        var developer = signInDeveloper();
        var category = category(actor(signInMaster()), EntryKind.EXPENSE);

        noStore(get(developer, "/api/finance/recurring"));
        expectProblem(
                post(
                        developer,
                        recurringBody(
                                category.id(), "100", 1, LocalDate.now(clock).plusDays(30), null),
                        "/api/finance/recurring"),
                HttpStatus.FORBIDDEN,
                "common.access_denied");
    }

    @Test
    void clientGetsNotFound() {
        var customer = signInClient();

        expectProblem(get(customer, "/api/finance/recurring"), HttpStatus.NOT_FOUND, "common.not_found");
        expectProblem(
                action(customer, "/api/finance/recurring/{id}/pause", UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void anonymousGetsUnauthenticated() {
        expectProblem(client.get().uri("/api/finance/recurring"), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
    }

    /** Шаблон из списка, прочитанного по адресу; его отсутствие — провал теста. */
    private JsonNode listed(Session session, UUID recurringId) {
        var found = json(get(session, "/api/finance/recurring")).values().stream()
                .filter(template -> field(template, "id").asString().equals(recurringId.toString()))
                .toList();
        assertThat(found).as("template " + recurringId).hasSize(1);
        return found.getFirst();
    }

    private static Map<String, @Nullable Object> recurringBody(
            UUID categoryId, String amount, int dayOfMonth, LocalDate startsOn, @Nullable LocalDate endsOn) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("categoryId", categoryId);
        body.put("amount", new BigDecimal(amount));
        body.put("comment", null);
        body.put("dayOfMonth", dayOfMonth);
        body.put("startsOn", startsOn.toString());
        body.put("endsOn", endsOn == null ? null : endsOn.toString());
        return body;
    }
}
