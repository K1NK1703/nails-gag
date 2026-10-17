package gag.nails.api.internal.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Поля записей в схеме OpenAPI 3.1 (спецификация API, §4.2): JSON слоя пишет каждый компонент записи, {@code null} —
 * явно, поэтому все поля обязательны ({@code required}), а помеченные {@link Nullable} ещё и допускают {@code null}.
 * Тип фронта получается {@code lastName: string | null}, а не {@code lastName?: string}. springdoc сам пометку не
 * видит: {@code @Nullable} из jspecify — аннотация типа, а не объявления.
 *
 * <p>Скалярное поле получает {@code "null"} в массиве {@code type} (и в {@code enum}, если он есть); ссылка на схему
 * заменяется на {@code oneOf} из неё и {@code {"type": "null"}}. Смотрятся только записи — ответы и тела запросов
 * слоя и модулей; имя поля — как у Jackson, с учётом {@link JsonProperty}.
 *
 * <p>Тела запросов — так же: фронт отправляет все поля, отсутствующее значение — явным {@code null} (Jackson его
 * принимает). Поле, которое Jackson опускает ({@link JsonInclude} не {@code ALWAYS} у поля или, без своей, у записи),
 * {@code null} не бывает: оно не обязательно и {@code null} не допускает — тип фронта {@code title?: string}. Записи
 * с такой аннотацией обязательность не ставится вовсе: тело ошибки {@code Problem} опускает пустое, его список задан
 * вручную.
 *
 * <p>Правило — только {@code @Nullable} и {@code @JsonInclude}: список {@code required} записи заменяется целиком,
 * поэтому {@code @Schema(requiredMode = …)} на компонентах записи не действует, а {@code null} разрешается по
 * {@code @Nullable}, не по {@code @Schema(nullable = …)}.
 */
final class NullableRecordComponents implements ModelConverter {

    private static final String NULL = "null";

    private static final String SCHEMAS = "#/components/schemas/";

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType annotatedType, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        var schema = chain.next().resolve(annotatedType, context, chain);
        // Без типа Java (схема задана только аннотацией) смотреть нечего.
        if (schema == null || annotatedType.getType() == null) {
            return schema;
        }
        var type = Json.mapper().constructType(annotatedType.getType()).getRawClass();
        if (type.isRecord()) {
            var model = model(schema, context);
            if (model != null && model.getProperties() != null) {
                markNullable(type, model);
                markRequired(type, model);
            }
        }
        return schema;
    }

    /** Сама схема записи или, если вернулась ссылка, определённая по ней модель. */
    private static @Nullable Schema<?> model(Schema<?> schema, ModelConverterContext context) {
        var ref = schema.get$ref();
        if (ref == null) {
            return schema;
        }
        return context.getDefinedModels().get(ref.startsWith(SCHEMAS) ? ref.substring(SCHEMAS.length()) : ref);
    }

    private static void markNullable(Class<?> type, Schema<?> model) {
        for (var component : type.getRecordComponents()) {
            if (!component.getAnnotatedType().isAnnotationPresent(Nullable.class) || !writesNull(type, component)) {
                continue;
            }
            var name = jsonName(component);
            var property = model.getProperties().get(name);
            if (property != null) {
                model.getProperties().put(name, nullable(property));
            }
        }
    }

    /** Запись или поле, которые Jackson может опустить, не обязательны; порядок списка swagger задаёт сам. */
    private static void markRequired(Class<?> type, Schema<?> model) {
        if (!alwaysIncluded(type.getAnnotation(JsonInclude.class))) {
            return;
        }
        var required = Arrays.stream(type.getRecordComponents())
                .filter(component -> writesNull(type, component))
                .map(NullableRecordComponents::jsonName)
                .filter(model.getProperties()::containsKey)
                .toList();
        model.setRequired(required.isEmpty() ? null : required);
    }

    /**
     * Пишет ли Jackson компонент всегда, в том числе {@code null}: своя {@link JsonInclude} поля важнее аннотации
     * записи, {@code USE_DEFAULTS} у поля отсылает к записи.
     */
    private static boolean writesNull(Class<?> type, RecordComponent component) {
        var own = component.getAccessor().getAnnotation(JsonInclude.class);
        if (own != null && own.value() != JsonInclude.Include.USE_DEFAULTS) {
            return own.value() == JsonInclude.Include.ALWAYS;
        }
        return alwaysIncluded(type.getAnnotation(JsonInclude.class));
    }

    /** {@code USE_DEFAULTS} — как у mapper слоя, а он пишет всё ({@link JsonConfiguration}). */
    private static boolean alwaysIncluded(@Nullable JsonInclude inclusion) {
        return inclusion == null
                || inclusion.value() == JsonInclude.Include.ALWAYS
                || inclusion.value() == JsonInclude.Include.USE_DEFAULTS;
    }

    /** {@link JsonProperty} не нацелена на компонент записи: Java переносит её на метод доступа и поле. */
    private static String jsonName(RecordComponent component) {
        var property = component.getAccessor().getAnnotation(JsonProperty.class);
        return property != null && !property.value().isEmpty() ? property.value() : component.getName();
    }

    /** Повторный вызов ничего не меняет: модель одной записи может прийти сюда не раз. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            return new Schema<>().oneOf(List.of(property, nullSchema()));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        // Без типа (любое значение) или уже с null — менять нечего.
        if (types.isEmpty() || types.contains(NULL)) {
            return property;
        }
        types.add(NULL);
        property.setTypes(types);
        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            property.getEnum().add(null);
        }
        return property;
    }

    private static Schema<?> nullSchema() {
        var schema = new Schema<>();
        schema.setTypes(Set.of(NULL));
        return schema;
    }
}
