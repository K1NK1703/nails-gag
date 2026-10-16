package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Схема OpenAPI, из которой фронт получает типы (спецификация API, §11): тело ошибки, ответ обратного вызова,
 * скрытые аргументы слоя, перечисления в пути. springdoc подключён только к тестам {@code api}, адреса — как в
 * {@code application-local.yaml}. Тестовые адреса ({@code /api/test/**}) в проверки не входят: в рабочем приложении их
 * нет.
 */
@ApiTest
@TestPropertySource(properties = {"springdoc.api-docs.path=/api/openapi.json", "springdoc.paths-to-match=/api/**"})
class OpenApiSchemaTest extends ApiTestSupport {

    private static final String PROBLEM_REF = "#/components/schemas/Problem";

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

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
                provider -> assertThat(enumValues(provider)).containsExactlyInAnyOrder("yandex", "vk", "fake"));
        var kinds = parameters(operations, "kind");
        assertThat(kinds).isNotEmpty();
        kinds.forEach(kind -> assertThat(enumValues(kind)).containsExactlyInAnyOrder("policy", "offer"));
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

    private static List<String> enumValues(JsonNode parameter) {
        return parameter.path("schema").path("enum").values().stream()
                .map(JsonNode::asString)
                .toList();
    }
}
