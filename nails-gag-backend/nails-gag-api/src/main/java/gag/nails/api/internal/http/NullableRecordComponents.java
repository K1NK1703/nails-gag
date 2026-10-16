package gag.nails.api.internal.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code null} в схеме OpenAPI 3.1 (спецификация API, §4.2): поле записи, чей тип помечен {@link Nullable}, допускает
 * {@code null}, и типы фронта получаются {@code string | null}. springdoc сам эту пометку не видит: {@code @Nullable}
 * из jspecify — аннотация типа, а не объявления. Остальные поля не меняются.
 *
 * <p>Скалярное поле получает {@code "null"} в массиве {@code type} (и в {@code enum}, если он есть); ссылка на схему
 * заменяется на {@code oneOf} из неё и {@code {"type": "null"}}. Смотрятся только записи — ответы и тела запросов
 * слоя и модулей; имя поля — как у Jackson, с учётом {@link JsonProperty}.
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
        var type = Json.mapper().constructType(annotatedType.getType()).getRawClass();
        if (schema != null && type.isRecord()) {
            var model = model(schema, context);
            if (model != null && model.getProperties() != null) {
                markNullable(type, model);
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
            if (!component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                continue;
            }
            var name = jsonName(component);
            var property = model.getProperties().get(name);
            if (property != null) {
                model.getProperties().put(name, nullable(property));
            }
        }
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
