package gag.nails.api.internal.access;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paging;
import gag.nails.identity.command.CreateRole;
import gag.nails.identity.command.UpdateRole;
import gag.nails.identity.model.AccessChangeView;
import gag.nails.identity.model.LoginEventView;
import gag.nails.identity.model.RoleView;
import gag.nails.identity.testing.FakeProfile;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Доступ (спецификация API, §7.7): роли, права сотрудников и журналы — по разделу «Доступ», его проверяет модуль;
 * встроенно он есть только у «Разработчика». Роли и журналы в базе общие для всех тестов: тест заводит свои роли с
 * уникальным кодом и меняет права только своим новым людям — правило последнего администратора они не задевают.
 */
@ApiTest
class AccessApiTest extends ApiTestSupport {

    @Test
    void rolesCrud() {
        var developer = signInDeveloper();
        var code = uniqueRoleCode();

        var created = json(
                createRole(developer, new CreateRole(code, "Администратор", Map.of(Section.CATALOG, AccessLevel.READ))),
                HttpStatus.CREATED);
        var roleId = UUID.fromString(field(created, "id").asString());
        assertThat(field(created, "code").asString()).isEqualTo(code);
        assertThat(field(created, "title").asString()).isEqualTo("Администратор");
        assertThat(field(created, "builtIn").asBoolean()).isFalse();
        assertThat(levels(field(created, "grants"))).isEqualTo(Map.of("CATALOG", "READ"));
        assertThat(ids(json(as(developer, client.get().uri("/api/access/roles")))))
                .contains(roleId);

        var updated = json(updateRole(
                developer,
                roleId,
                new UpdateRole("Старший администратор", Map.of(Section.CLIENTS, AccessLevel.WRITE))));
        assertThat(field(updated, "id").asString()).isEqualTo(roleId.toString());
        assertThat(field(updated, "code").asString()).isEqualTo(code);
        assertThat(field(updated, "title").asString()).isEqualTo("Старший администратор");
        assertThat(levels(field(updated, "grants"))).isEqualTo(Map.of("CLIENTS", "WRITE"));

        deleteRole(developer, roleId).exchange().expectStatus().isNoContent();
        assertThat(access.roles(developerActor())).extracting(RoleView::id).doesNotContain(roleId);
    }

    @Test
    void builtInRoleCannotBeDeleted() {
        var master = builtInRole("MASTER");

        expectProblem(deleteRole(signInDeveloper(), master.id()), HttpStatus.CONFLICT, "identity.role_built_in");
        assertThat(access.roles(developerActor())).extracting(RoleView::id).contains(master.id());
    }

    @Test
    void staffAndAccount() {
        var developer = signInDeveloper();
        var master = signInMaster();
        var subject = uniqueSubject();
        var name = "Клиент " + subject;
        var customer = signIn(new FakeProfile(subject, uniquePhone(), null, name, null));

        var staff = json(as(developer, client.get().uri("/api/access/staff")));
        var masterEntry = byAccountId(staff, master.accountId());
        assertThat(roleCodes(field(masterEntry, "roles"))).containsExactly("MASTER");
        assertThat(accountIds(staff)).doesNotContain(customer.accountId());

        var account = json(as(developer, client.get().uri("/api/access/accounts/{id}", customer.accountId())));
        assertThat(field(account, "accountId").asString())
                .isEqualTo(customer.accountId().toString());
        assertThat(field(account, "clientId").asString())
                .isEqualTo(actor(customer).requireClientId().toString());
        assertThat(field(account, "displayName").asString()).isEqualTo(name);
        assertThat(field(account, "roles").isEmpty()).isTrue();
        assertThat(levels(field(account, "overrides"))).isEmpty();
        assertThat(levels(field(account, "effective"))).isEqualTo(allSections(AccessLevel.NONE));
        assertThat(field(account, "blocked").asBoolean()).isFalse();
    }

    @Test
    void unknownAccountIsNotFound() {
        var problem = json(
                as(signInDeveloper(), client.get().uri("/api/access/accounts/{id}", UUID.randomUUID())),
                HttpStatus.NOT_FOUND);

        assertThat(field(problem, "code").asString()).isEqualTo("identity.account_not_found");
    }

    @Test
    void assignAndRevokeRole() {
        var developer = signInDeveloper();
        var customer = signInClient();
        var master = builtInRole("MASTER");

        var assigned = json(assignRole(developer, customer.accountId(), master.id()));
        assertThat(field(assigned, "accountId").asString())
                .isEqualTo(customer.accountId().toString());
        assertThat(roleCodes(field(assigned, "roles"))).containsExactly("MASTER");
        assertThat(field(field(assigned, "effective"), "BOOKINGS").asString()).isEqualTo("WRITE");
        assertThat(actor(customer).canWrite(Section.BOOKINGS)).isTrue();

        var revoked = json(revokeRole(developer, customer.accountId(), master.id()));
        assertThat(field(revoked, "roles").isEmpty()).isTrue();
        assertThat(levels(field(revoked, "effective"))).isEqualTo(allSections(AccessLevel.NONE));
        assertThat(access.account(customer.accountId(), developerActor()).roles())
                .isEmpty();
    }

    @Test
    void overrideSetAndRemove() {
        var developer = signInDeveloper();
        var customer = signInClient();

        var set = json(setOverride(developer, customer.accountId(), "booking-settings", Map.of("level", "READ")));
        assertThat(levels(field(set, "overrides"))).isEqualTo(Map.of("BOOKING_SETTINGS", "READ"));
        assertThat(field(field(set, "effective"), "BOOKING_SETTINGS").asString())
                .isEqualTo("READ");
        assertThat(access.account(customer.accountId(), developerActor()).overrides())
                .isEqualTo(Map.of(Section.BOOKING_SETTINGS, AccessLevel.READ));

        var removed = json(removeOverride(developer, customer.accountId(), "booking-settings"));
        assertThat(levels(field(removed, "overrides"))).isEmpty();
        assertThat(levels(field(removed, "effective"))).isEqualTo(allSections(AccessLevel.NONE));
        assertThat(access.account(customer.accountId(), developerActor()).overrides())
                .isEmpty();
    }

    /** {@code level: null} снимал бы переопределение — для этого есть {@code DELETE}, тело без уровня отклоняется. */
    @Test
    void overrideWithoutLevelIsBadRequest() {
        var customer = signInClient();

        var problem = json(
                setOverride(signInDeveloper(), customer.accountId(), "booking-settings", Map.of()),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("level");
        assertThat(access.account(customer.accountId(), developerActor()).overrides())
                .isEmpty();
    }

    /** Раздел в пути — код через дефис; опечатка в нём — неверное значение параметра, а не {@code 404}. */
    @Test
    void unknownSectionIsBadRequest() {
        var customer = signInClient();

        var problem = json(
                setOverride(signInDeveloper(), customer.accountId(), "bookings-settings", Map.of("level", "READ")),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("section");
        assertThat(access.account(customer.accountId(), developerActor()).overrides())
                .isEmpty();
    }

    /** Раздел в пути — только через дефис: имя константы Java и другой регистр — неверное значение, ничего не меняется. */
    @Test
    void sectionInJavaCaseIsBadRequest() {
        var developer = signInDeveloper();
        var customer = signInClient();

        for (var section : List.of("BOOKING_SETTINGS", "Booking-Settings", "booking_settings", " booking-settings")) {
            var problem = json(
                    setOverride(developer, customer.accountId(), section, Map.of("level", "READ")),
                    HttpStatus.BAD_REQUEST);

            assertThat(field(problem, "code").asString()).as(section).isEqualTo("common.validation");
            assertThat(field(field(problem, "errors").path(0), "field").asString())
                    .as(section)
                    .isEqualTo("section");
        }
        assertThat(access.account(customer.accountId(), developerActor()).overrides())
                .isEmpty();
    }

    /**
     * Журналы общие для всей базы: страница сверяется с той же страницей модуля, прочитанной сразу перед запросом, —
     * чтение журнала само строк не добавляет. Порядок модуля однозначен (время, затем идентификатор). На двух страницах
     * по две строки нужно хотя бы четыре строки в каждом журнале — тест заводит их сам, даже на пустой базе: четыре
     * входа новых людей и четыре изменения прав.
     */
    @Test
    void journalsArePaged() {
        var developer = signInDeveloper();
        var customer = signInClient();
        signInClient();
        signInClient();
        var master = builtInRole("MASTER");
        for (var round = 0; round < 2; round++) {
            access.assignRole(customer.accountId(), master.id(), developerActor());
            access.revokeRole(customer.accountId(), master.id(), developerActor());
        }
        assertThat(access.accessJournal(new Paging(0, 1), developerActor()).total())
                .isGreaterThanOrEqualTo(4);
        assertThat(access.loginJournal(new Paging(0, 1), developerActor()).total())
                .isGreaterThanOrEqualTo(4);

        for (var page = 0; page < 2; page++) {
            var expected = access.accessJournal(new Paging(page, 2), developerActor());
            var journal = json(as(developer, client.get().uri("/api/access/journal?page={page}&size=2", page)));

            assertThat(field(journal, "page").asInt()).isEqualTo(page);
            assertThat(field(journal, "size").asInt()).isEqualTo(2);
            assertThat(field(journal, "total").asLong()).isEqualTo(expected.total());
            var items = field(journal, "items");
            assertThat(ids(items))
                    .containsExactlyElementsOf(
                            expected.items().stream().map(AccessChangeView::id).toList())
                    .hasSize(2);
            assertThat(field(items.path(0), "kind").asString())
                    .isEqualTo(expected.items().getFirst().kind().name());
        }
        for (var page = 0; page < 2; page++) {
            var expected = access.loginJournal(new Paging(page, 2), developerActor());
            var journal = json(as(developer, client.get().uri("/api/access/login-journal?page={page}&size=2", page)));

            assertThat(field(journal, "page").asInt()).isEqualTo(page);
            assertThat(field(journal, "size").asInt()).isEqualTo(2);
            assertThat(field(journal, "total").asLong()).isEqualTo(expected.total());
            var items = field(journal, "items");
            assertThat(ids(items))
                    .containsExactlyElementsOf(
                            expected.items().stream().map(LoginEventView::id).toList())
                    .hasSize(2);
            assertThat(field(items.path(0), "outcome").asString())
                    .isEqualTo(expected.items().getFirst().outcome().name());
        }
    }

    @Test
    void journalPageSizeIsLimited() {
        var problem = json(
                as(signInDeveloper(), client.get().uri("/api/access/journal?size={size}", Paging.MAX_SIZE + 1)),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("size");
    }

    /** У «Мастера» раздела «Доступ» нет: сотрудник получает честный отказ про раздел, ничего не меняется. */
    @Test
    void masterGetsForbidden() {
        var master = signInMaster();
        var code = uniqueRoleCode();

        expectProblem(as(master, client.get().uri("/api/access/roles")), HttpStatus.FORBIDDEN, "common.access_denied");
        expectProblem(
                createRole(master, new CreateRole(code, "Роль", Map.of())),
                HttpStatus.FORBIDDEN,
                "common.access_denied");
        expectProblem(
                as(master, client.get().uri("/api/access/journal")), HttpStatus.FORBIDDEN, "common.access_denied");
        assertThat(access.roles(developerActor())).extracting(RoleView::code).doesNotContain(code);
    }

    /** Клиент не отличает закрытый раздел от несуществующего адреса (§4.6). */
    @Test
    void clientGetsNotFound() {
        var customer = signInClient();

        expectProblem(
                as(customer, client.get().uri("/api/access/accounts/{id}", customer.accountId())),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        expectProblem(
                setOverride(customer, customer.accountId(), "access", Map.of("level", "WRITE")),
                HttpStatus.NOT_FOUND,
                "common.not_found");
        assertThat(actor(customer).canRead(Section.ACCESS)).isFalse();
    }

    @Test
    void anonymousGetsUnauthenticated() {
        expectProblem(client.get().uri("/api/access/staff"), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
    }

    private RequestHeadersSpec<?> createRole(Session session, CreateRole command) {
        return as(session, csrf(client.post().uri("/api/access/roles")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    private RequestHeadersSpec<?> updateRole(Session session, UUID roleId, UpdateRole command) {
        return as(session, csrf(client.patch().uri("/api/access/roles/{id}", roleId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    private RequestHeadersSpec<?> deleteRole(Session session, UUID roleId) {
        return as(session, csrf(client.delete().uri("/api/access/roles/{id}", roleId)));
    }

    private RequestHeadersSpec<?> assignRole(Session session, UUID accountId, UUID roleId) {
        return as(session, csrf(client.post().uri("/api/access/accounts/{id}/roles", accountId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("roleId", roleId));
    }

    private RequestHeadersSpec<?> revokeRole(Session session, UUID accountId, UUID roleId) {
        return as(session, csrf(client.delete().uri("/api/access/accounts/{id}/roles/{roleId}", accountId, roleId)));
    }

    private RequestHeadersSpec<?> setOverride(
            Session session, UUID accountId, String section, Map<String, String> body) {
        return as(session, csrf(client.put().uri("/api/access/accounts/{id}/overrides/{section}", accountId, section)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> removeOverride(Session session, UUID accountId, String section) {
        return as(
                session,
                csrf(client.delete().uri("/api/access/accounts/{id}/overrides/{section}", accountId, section)));
    }

    private RoleView builtInRole(String code) {
        return access.roles(developerActor()).stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    /** Код роли, которого ещё нет: заглавные латинские буквы и цифры, как требует модуль. */
    private static String uniqueRoleCode() {
        return "API_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    /** Уровни по разделам из JSON-объекта {@code {"CATALOG": "READ"}}. */
    private static Map<String, String> levels(JsonNode levels) {
        assertThat(levels.isObject()).as("levels " + levels).isTrue();
        return levels.properties().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().asString()));
    }

    /** Все девять разделов с одним уровнем — так модуль пишет итог. */
    private static Map<String, String> allSections(AccessLevel level) {
        return Arrays.stream(Section.values()).collect(Collectors.toMap(Section::name, section -> level.name()));
    }

    private static List<String> roleCodes(JsonNode roles) {
        return roles.values().stream()
                .map(role -> field(role, "code").asString())
                .toList();
    }

    private static List<UUID> accountIds(JsonNode accounts) {
        return accounts.values().stream()
                .map(account -> UUID.fromString(field(account, "accountId").asString()))
                .toList();
    }

    private static JsonNode byAccountId(JsonNode accounts, UUID accountId) {
        Map<UUID, JsonNode> byId = accounts.values().stream()
                .collect(Collectors.toMap(
                        account -> UUID.fromString(field(account, "accountId").asString()), Function.identity()));
        assertThat(byId).as("account " + accountId).containsKey(accountId);
        return byId.get(accountId);
    }
}
