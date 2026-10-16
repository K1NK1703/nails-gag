package gag.nails.api.internal.http;

import gag.nails.api.internal.error.Problem;
import gag.nails.common.actor.Actor;
import gag.nails.identity.model.ClientInfo;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;

/**
 * Схема OpenAPI для локального запуска (спецификация API, §5.5): springdoc подключён в {@code app} только для
 * {@code bootRun}, без него эта настройка не загружается — условие проверяется по имени класса, и других ссылок на
 * этот класс нет. Из схемы фронт получает типы TypeScript (§11).
 *
 * <p>Схема — OpenAPI 3.1 ({@code springdoc.api-docs.version: openapi_3_1}, по умолчанию у springdoc 3): настройка
 * рассчитана только на неё — {@code null} в полях пишется массивом типов ({@link NullableRecordComponents}).
 *
 * <p>springdoc не знает резолверов слоя и принял бы {@code Actor}, {@code Optional<Actor>} и {@code ClientInfo} за
 * параметры запроса: они скрываются. Перечисления в пути и параметрах запроса показываются так, как их принимает адрес,
 * — в нижнем регистре через дефис ({@link WebConfiguration#KEBAB_CASE_TYPES}). Тело ошибки {@link Problem} — ответ
 * {@code default} каждой операции: его пишут и обработчик ошибок контроллеров, и фильтры ({@code 403}, {@code 429}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springdoc.core.utils.SpringDocUtils")
class OpenApiConfiguration {

    /** Обратный вызов провайдера: всегда {@code 303} на страницу сайта (§6.3). */
    private static final String CALLBACK_PATH = ApiPaths.AUTH + "/{provider}/callback";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String SCHEMAS = "#/components/schemas/";

    OpenApiConfiguration() {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class, ClientInfo.class);
    }

    /**
     * {@code Optional<Actor>} springdoc видит как {@code Optional} и не пропускает по типу: такой параметр убирается
     * здесь. Перечисления в пути и запросе получают значения через дефис — только скалярный параметр со встроенным
     * {@code enum}; список перечислений ({@code List<Section>}) или ссылка на схему перечисления остались бы с именами
     * Java.
     */
    @Bean
    ParameterCustomizer apiParameterCustomizer() {
        return (parameter, methodParameter) -> {
            if (optionalActor(methodParameter)) {
                return null;
            }
            if (parameter != null
                    && ("path".equals(parameter.getIn()) || "query".equals(parameter.getIn()))
                    && WebConfiguration.KEBAB_CASE_TYPES.contains(methodParameter.getParameterType())) {
                kebabCaseEnum(parameter);
            }
            return parameter;
        };
    }

    /** springdoc регистрирует бины {@link ModelConverter} в общем {@link ModelConverters} схемы 3.1. */
    @Bean
    ModelConverter nullableRecordComponents() {
        return new NullableRecordComponents();
    }

    /**
     * Схема {@code Problem} в {@code components}, ответ {@code default} с ней у каждой операции; у обратного вызова
     * провайдера — {@code 303} вместо всех ответов, кроме {@code default}. Описания ответов — по-английски, как у
     * ответов, которые пишет springdoc.
     */
    @Bean
    OpenApiCustomizer apiOpenApiCustomizer() {
        return openApi -> {
            addProblemSchemas(openApi);
            if (openApi.getPaths() != null) {
                addDefaultProblemResponses(openApi.getPaths());
                replaceCallbackResponses(openApi.getPaths());
            }
        };
    }

    private static boolean optionalActor(MethodParameter parameter) {
        return parameter.getParameterType() == Optional.class
                && ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == Actor.class;
    }

    @SuppressWarnings("unchecked")
    private static void kebabCaseEnum(Parameter parameter) {
        var schema = (Schema<Object>) parameter.getSchema();
        if (schema != null && schema.getEnum() != null) {
            schema.setEnum(schema.getEnum().stream()
                    .map(value -> (Object) WebConfiguration.kebabCase(value.toString()))
                    .toList());
        }
    }

    /**
     * {@code Problem} и {@code FieldError} — схемы по записям, как у ответов. {@code Problem} опускает пустое
     * ({@code NON_EMPTY}), и {@link NullableRecordComponents} его обязательность не трогает: обязательны поля, которые
     * есть в каждом теле ошибки, а {@code title}, {@code params}, {@code errors} и {@code traceId} бывают опущены.
     */
    private static void addProblemSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        var components = openApi.getComponents();
        ModelConverters.getInstance(true).readAll(Problem.class).forEach(components::addSchemas);
        Schema<?> problem = components.getSchemas().get("Problem");
        problem.setRequired(List.of("type", "status", "instance", "code"));
    }

    /**
     * Ошибку в {@code problem+json} может вернуть любой адрес: обработчик ошибок контроллеров, а до контроллера —
     * фильтры ({@code 403 common.csrf}, {@code 429}).
     */
    private static void addDefaultProblemResponses(Paths paths) {
        paths.values().stream()
                .flatMap(path -> path.readOperations().stream())
                .forEach(operation -> operation.getResponses().addApiResponse(ApiResponses.DEFAULT, problem()));
    }

    /**
     * Обратный вызов отвечает браузеру переходом при любом исходе; {@code default} остаётся — отказ {@code 429} пишет
     * фильтр до контроллера.
     */
    private static void replaceCallbackResponses(Paths paths) {
        var callback = paths.get(CALLBACK_PATH);
        if (callback == null || callback.getGet() == null) {
            return;
        }
        var responses = callback.getGet().getResponses();
        var problem = responses.get(ApiResponses.DEFAULT);
        responses.clear();
        responses.addApiResponse("303", seeOther());
        if (problem != null) {
            responses.addApiResponse(ApiResponses.DEFAULT, problem);
        }
    }

    private static ApiResponse problem() {
        return new ApiResponse()
                .description("Problem (RFC 9457)")
                .content(new Content()
                        .addMediaType(PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref(SCHEMAS + "Problem"))));
    }

    private static ApiResponse seeOther() {
        var uri = new Schema<String>();
        uri.setTypes(Set.of("string"));
        uri.setFormat("uri");
        return new ApiResponse()
                .description("Redirect to a site page: cabinet, registration, link confirmation or failure")
                .addHeaderObject(
                        HttpHeaders.LOCATION, new Header().required(true).schema(uri));
    }
}
