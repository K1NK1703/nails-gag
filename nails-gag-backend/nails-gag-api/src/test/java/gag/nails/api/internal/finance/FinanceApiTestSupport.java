package gag.nails.api.internal.finance;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTestSupport;
import gag.nails.common.actor.Actor;
import gag.nails.finance.FinanceCategories;
import gag.nails.finance.FinanceEntries;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.testsupport.TestTimeConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Обвязка HTTP-тестов финансов. Категории общие для всей базы, поэтому тест заводит свои с уникальным названием;
 * ручные записи — на днях {@link #entryDay()}, которых этот прогон не выдаёт никому другому. Изменять финансы
 * разработчик не может (у него только чтение раздела), поэтому данные готовит мастер.
 */
abstract class FinanceApiTestSupport extends ApiTestSupport {

    /**
     * На сколько дней до {@link TestTimeConfiguration#START} уходит следующий {@link #entryDay()}. Статический — общий
     * для всех классов тестов прогона, поэтому день не выдаётся дважды; случайное начало в 400–8000 днях лишь разносит
     * дни по календарю. До {@code 2000-01-01} (нижний предел дня записи) от начала остаётся больше 1700 дней — больше,
     * чем вызовов за прогон.
     */
    private static final AtomicLong DAYS_BACK =
            new AtomicLong(ThreadLocalRandom.current().nextLong(400, 8000));

    @Autowired
    FinanceCategories categories;

    @Autowired
    FinanceEntries entries;

    /**
     * День до {@link TestTimeConfiguration#START}, которого ещё не выдавал этот прогон, — от {@code START}, а не от
     * текущих часов, поэтому не зависит от их сдвига. Другие тесты финансов кладут ручные записи только на свои такие
     * дни, а записи на визит — на дни {@link #freshDay()}, которые позже {@code START}: чужих операций на этом дне нет.
     */
    LocalDate entryDay() {
        return LocalDate.ofInstant(TestTimeConfiguration.START, clock.getZone()).minusDays(DAYS_BACK.getAndIncrement());
    }

    /** Новая категория вида с уникальным названием; её заводит мастер. */
    CategoryView category(Actor master, EntryKind kind) {
        return categories.create(new CreateCategory(kind, uniqueCategoryName()), master);
    }

    /** Ручная запись без комментария; её вносит мастер. */
    EntryView entry(Actor master, UUID categoryId, String amount, LocalDate day) {
        return entries.add(new AddEntry(categoryId, new BigDecimal(amount), day, null), master);
    }

    /** Название категории, которого ещё нет в базе: до 60 символов. */
    static String uniqueCategoryName() {
        return "Категория " + UUID.randomUUID();
    }

    /** Тело ответа {@code 200}, который запрещено сохранять: {@code Cache-Control: no-store}. */
    static JsonNode noStore(RequestHeadersSpec<?> spec) {
        return noStore(spec, HttpStatus.OK);
    }

    /** Тело ответа с ожидаемым статусом, который запрещено сохранять: {@code Cache-Control: no-store}. */
    static JsonNode noStore(RequestHeadersSpec<?> spec, HttpStatus status) {
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    /** Ошибка проверки по одному полю: {@code 400 common.validation} и имя поля первого нарушения. */
    static void expectInvalid(RequestHeadersSpec<?> spec, String field) {
        var problem = json(spec, HttpStatus.BAD_REQUEST);
        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo(field);
    }

    /** {@code GET} от имени вошедшего. */
    RequestHeadersSpec<?> get(Session session, String uri, Object... variables) {
        return as(session, client.get().uri(uri, variables));
    }

    /** {@code POST} с телом JSON от имени вошедшего. */
    RequestHeadersSpec<?> post(Session session, Object body, String uri, Object... variables) {
        return as(session, csrf(client.post().uri(uri, variables)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** {@code POST} без тела от имени вошедшего: переход состояния. */
    RequestHeadersSpec<?> action(Session session, String uri, Object... variables) {
        return as(session, csrf(client.post().uri(uri, variables)));
    }

    /** {@code PATCH} с телом JSON от имени вошедшего. */
    RequestHeadersSpec<?> patch(Session session, Object body, String uri, Object... variables) {
        return as(session, csrf(client.patch().uri(uri, variables)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** {@code PUT} с телом JSON от имени вошедшего. */
    RequestHeadersSpec<?> put(Session session, Object body, String uri, Object... variables) {
        return as(session, csrf(client.put().uri(uri, variables)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** {@code DELETE} от имени вошедшего. */
    RequestHeadersSpec<?> delete(Session session, String uri, Object... variables) {
        return as(session, csrf(client.delete().uri(uri, variables)));
    }
}
