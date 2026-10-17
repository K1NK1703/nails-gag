package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Section;
import gag.nails.identity.model.Provider;
import io.swagger.v3.oas.models.PathItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Схема OpenAPI, из которой фронт получает типы (спецификация API, §11): тело ошибки, ответ обратного вызова,
 * скрытые аргументы слоя, перечисления в пути, {@code null} в полях. springdoc подключён только к тестам {@code api} и
 * включён только здесь ({@link ApiTest} его выключает), настройки — как в {@code application-local.yaml}. Тестовые адреса
 * ({@code /api/test/**}) в проверки не входят: в рабочем приложении их нет.
 */
@ApiTest
@TestPropertySource(
        properties = {
            "springdoc.api-docs.enabled=true",
            "springdoc.api-docs.version=openapi_3_1",
            "springdoc.api-docs.path=/api/openapi.json",
            "springdoc.paths-to-match=/api/**",
            "springdoc.default-produces-media-type=application/json"
        })
class OpenApiSchemaTest extends ApiTestSupport {

    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    /** {@code operationId}: {@code <контроллер>_<метод>}, обе части — camelCase с маленькой буквы. */
    private static final Pattern OPERATION_ID = Pattern.compile("[a-z][A-Za-z]*_[a-z][A-Za-z0-9]*");

    /** Переменная пути с регулярным выражением ({@code {provider:yandex|vk|fake}}): в схеме — только имя. */
    private static final Pattern PATH_VARIABLE_REGEX = Pattern.compile("\\{([^}:]+):[^}]*}");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /** Ключи операций в элементе {@code paths}: имена методов HTTP в нижнем регистре. */
    private static final Set<String> HTTP_METHODS = Arrays.stream(PathItem.HttpMethod.values())
            .map(method -> method.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** springdoc 3 по умолчанию пишет OpenAPI 3.1: {@code null} в полях — массив типов, а не {@code nullable}. */
    @Test
    void schemaIsOpenApi31() {
        assertThat(openApi().path("openapi").asString()).startsWith("3.1.");
    }

    /**
     * Ответ пишет каждый компонент записи, {@code null} — явно (§4.2): все поля обязательны, и тип фронта —
     * {@code lastName: string | null}, а не {@code lastName?: …}. Тела запросов — так же. {@code Problem} опускает пустое
     * и сохраняет свой список.
     */
    @Test
    void recordComponentsAreRequired() {
        var schemas = openApi().path("components").path("schemas");

        assertThat(required(schemas.path("ClientView")))
                .containsExactlyInAnyOrderElementsOf(
                        schemas.path("ClientView").path("properties").propertyNames())
                .contains("lastName", "accountId");
        assertThat(required(schemas.path("Me"))).containsExactlyInAnyOrder("account", "client");
        assertThat(required(schemas.path("NewDocumentVersion")))
                .containsExactlyInAnyOrder("version", "effectiveFrom", "text");
        assertThat(required(schemas.path("Problem"))).containsExactlyInAnyOrder("type", "status", "instance", "code");
        assertThat(required(schemas.path("FieldError"))).containsExactlyInAnyOrder("field", "reason");
    }

    /**
     * Компонент записи с {@code @Nullable} допускает {@code null} (типы фронта — {@code string | null}, спецификация
     * API, §4.2), остальные — нет. Ссылка на схему — через {@code oneOf} с {@code null}.
     */
    @Test
    void nullableComponentsAreNullable() {
        var schemas = openApi().path("components").path("schemas");

        var client = schemas.path("ClientView").path("properties");
        assertThat(types(client.path("lastName"))).containsExactlyInAnyOrder("string", "null");
        assertThat(types(client.path("accountId"))).containsExactlyInAnyOrder("string", "null");
        assertThat(client.path("accountId").path("format").asString()).isEqualTo("uuid");
        assertThat(types(client.path("firstName"))).containsExactly("string");
        assertThat(types(client.path("id"))).containsExactly("string");
        assertThat(types(client.path("source"))).containsExactly("string");
        assertThat(types(client.path("blocked"))).containsExactly("boolean");

        var me = schemas.path("Me").path("properties");
        assertThat(me.path("client").path("oneOf").values())
                .extracting(option ->
                        option.path("$ref").asString() + option.path("type").asString())
                .containsExactlyInAnyOrder("#/components/schemas/ClientView", "null");
        assertThat(me.path("account").path("$ref").asString()).isEqualTo("#/components/schemas/AccountView");
        assertThat(me.path("account").path("oneOf").isMissingNode()).isTrue();
    }

    @Test
    void problemSchemaIsDefaultResponse() {
        var schema = openApi();

        var problem = schema.path("components").path("schemas").path("Problem");
        assertThat(problem.path("properties").propertyNames())
                .contains("type", "title", "status", "instance", "code", "params", "errors", "traceId");
        assertThat(problem.path("required").values().stream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("type", "status", "instance", "code");
        var operations = operations(schema);
        assertThat(operations).isNotEmpty();
        for (var operation : operations) {
            assertThat(operation
                            .node()
                            .path("responses")
                            .path("default")
                            .path("content")
                            .path("application/problem+json")
                            .path("schema")
                            .path("$ref")
                            .asString())
                    .as(operation.name())
                    .isEqualTo(PROBLEM_REF);
        }
    }

    @Test
    void callbackIsSeeOther() {
        var responses = openApi()
                .path("paths")
                .path("/api/auth/{provider}/callback")
                .path("get")
                .path("responses");

        assertThat(responses.propertyNames()).containsExactlyInAnyOrder("303", "default");
        assertThat(responses.path("303").path("headers").path("Location").isMissingNode())
                .isFalse();
    }

    @Test
    void actorAndClientInfoAreHidden() {
        var schema = openApi();

        for (var operation : operations(schema)) {
            for (var parameter : operation.node().path("parameters").values()) {
                assertThat(parameter.path("name").asString())
                        .as(operation.name())
                        .isNotIn("actor", "client");
            }
        }
        assertThat(schema.path("components").path("schemas").propertyNames()).doesNotContain("Actor", "ClientInfo");
    }

    @Test
    void pathEnumsAreKebabCase() {
        var operations = operations(openApi());

        var providers = parameters(operations, "provider");
        assertThat(providers).isNotEmpty();
        providers.forEach(
                provider -> assertThat(enumValues(provider)).containsExactlyElementsOf(kebabCase(Provider.values())));
        var kinds = parameters(operations, "kind");
        assertThat(kinds).isNotEmpty();
        kinds.forEach(kind -> assertThat(enumValues(kind)).containsExactlyElementsOf(kebabCase(DocumentKind.values())));
    }

    /** Перечисление в параметре запроса — тоже через дефис: {@code ?scope=active|history}. */
    @Test
    void queryEnumsAreKebabCase() {
        var scopes = parameters(operations(openApi()), "scope");

        assertThat(scopes).isNotEmpty();
        scopes.forEach(scope -> {
            assertThat(scope.path("in").asString()).isEqualTo("query");
            assertThat(enumValues(scope)).containsExactly("active", "history");
        });
    }

    /**
     * Схема полная: в ней каждый адрес контроллеров приложения ({@link #controllerOperations()}) с его методом и ничего
     * сверх них — фронт не останется без типа для адреса.
     */
    @Test
    void everyApiControllerIsInSchema() {
        var mapped = controllerOperations().keySet();

        var documented = operations(openApi()).stream().map(Operation::name).toList();

        assertThat(mapped).hasSizeGreaterThan(80);
        assertThat(documented).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(mapped);
    }

    /**
     * {@code operationId} — {@code <контроллер>_<метод>}: имя класса контроллера без {@code Controller} с маленькой
     * буквы и имя метода Java ({@code ServicesController.create} → {@code services_create}). Имя не зависит от порядка
     * обхода контроллеров, как суффиксы {@code create_1} у springdoc, и по нему тип операции фронта находится в коде.
     */
    @Test
    void operationIdsAreControllerAndMethod() {
        var ids = new HashMap<String, String>();
        for (var operation : operations(openApi())) {
            ids.put(operation.name(), operation.node().path("operationId").asString());
        }

        assertThat(ids.values())
                .doesNotHaveDuplicates()
                .allMatch(id -> OPERATION_ID.matcher(id).matches());
        assertThat(ids).containsExactlyInAnyOrderEntriesOf(controllerOperations());
        assertThat(ids)
                .containsEntry("post /api/services", "services_create")
                .containsEntry("put /api/access/accounts/{id}/overrides/{section}", "staffAccess_setOverride");
    }

    /** Раздел админки в пути — через дефис, как его принимает адрес: {@code …/overrides/booking-settings}. */
    @Test
    void sectionPathIsKebabCase() {
        var sections = parameters(operations(openApi()), "section");

        assertThat(sections).hasSize(2);
        sections.forEach(section -> {
            assertThat(section.path("in").asString()).isEqualTo("path");
            assertThat(enumValues(section)).containsExactlyElementsOf(kebabCase(Section.values()));
        });
    }

    /**
     * Успешные ответы — {@code application/json}, а не {@code *}{@code /*} springdoc по умолчанию: генератор типов
     * фронта берёт тип ответа по типу содержимого. Ответы без тела ({@code 204}, {@code 303}) содержимого не имеют.
     */
    @Test
    void successResponsesAreJson() {
        var operations = operations(openApi());
        var withBody = 0;

        for (var operation : operations) {
            for (var response : operation.node().path("responses").properties()) {
                if (response.getKey().equals("default")) {
                    continue;
                }
                var content = response.getValue().path("content");
                if (content.isMissingNode()) {
                    assertThat(response.getKey()).as(operation.name()).isIn("204", "303");
                    continue;
                }
                withBody++;
                assertThat(content.propertyNames())
                        .as(operation.name() + " " + response.getKey())
                        .containsExactly("application/json");
            }
        }
        assertThat(withBody).isGreaterThan(operations.size() / 2);
    }

    /**
     * Адреса {@code /api/**} контроллеров приложения: «метод адрес» в виде ключа схемы → ожидаемый {@code operationId}.
     * Тестовые адреса ({@code /api/test/**}) и {@code /error} в счёт не идут, адреса springdoc
     * ({@code /api/openapi.json}) — тоже: их контроллеры не из приложения.
     */
    private Map<String, String> controllerOperations() {
        var mapped = new HashMap<String, String>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("gag.nails.")) {
                return;
            }
            var controller = method.getBeanType().getSimpleName().replaceFirst("Controller$", "");
            var operationId = Character.toLowerCase(controller.charAt(0)) + controller.substring(1) + "_"
                    + method.getMethod().getName();
            for (var pattern : info.getPatternValues()) {
                if (pattern.startsWith("/api/") && !pattern.startsWith("/api/test/")) {
                    var path = PATH_VARIABLE_REGEX.matcher(pattern).replaceAll("{$1}");
                    info.getMethodsCondition()
                            .getMethods()
                            .forEach(httpMethod ->
                                    mapped.put(httpMethod.name().toLowerCase(Locale.ROOT) + " " + path, operationId));
                }
            }
        });
        return mapped;
    }

    /** Операция схемы: {@code name} — метод и адрес для сообщений проверок. */
    private record Operation(String name, JsonNode node) {}

    private JsonNode openApi() {
        var body = client.get()
                .uri("/api/openapi.json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JsonMapper.builder().build().readTree(Objects.requireNonNull(body));
    }

    /** Все операции, кроме тестовых адресов {@code /api/test/**}. */
    private static List<Operation> operations(JsonNode schema) {
        var operations = new ArrayList<Operation>();
        for (var path : schema.path("paths").properties()) {
            if (path.getKey().startsWith("/api/test/")) {
                continue;
            }
            for (var method : path.getValue().properties()) {
                if (HTTP_METHODS.contains(method.getKey())) {
                    operations.add(new Operation(method.getKey() + " " + path.getKey(), method.getValue()));
                }
            }
        }
        return operations;
    }

    private static List<JsonNode> parameters(List<Operation> operations, String name) {
        return operations.stream()
                .flatMap(operation -> operation.node().path("parameters").values().stream())
                .filter(parameter -> parameter.path("name").asString().equals(name))
                .toList();
    }

    private static List<String> kebabCase(Enum<?>[] constants) {
        return Arrays.stream(constants)
                .map(constant -> WebConfiguration.kebabCase(constant.name()))
                .toList();
    }

    private static List<String> required(JsonNode schema) {
        return schema.path("required").values().stream().map(JsonNode::asString).toList();
    }

    /** {@code type} схемы как список: в OpenAPI 3.1 это строка или массив строк. */
    private static List<String> types(JsonNode schema) {
        var type = schema.path("type");
        return type.isArray() ? type.values().stream().map(JsonNode::asString).toList() : List.of(type.asString());
    }

    private static List<String> enumValues(JsonNode parameter) {
        return parameter.path("schema").path("enum").values().stream()
                .map(JsonNode::asString)
                .toList();
    }
}
