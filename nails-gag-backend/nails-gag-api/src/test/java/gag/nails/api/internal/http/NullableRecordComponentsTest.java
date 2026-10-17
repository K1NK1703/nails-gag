package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Компоненты записи в схеме OpenAPI 3.1: все обязательны, {@code @Nullable} допускают {@code null} — без контекста, на
 * своём реестре.
 */
class NullableRecordComponentsTest {

    enum Level {
        READ,
        WRITE
    }

    record Inner(String value) {}

    record Sample(
            String required,
            @Nullable String text,
            @Nullable Level level,
            Level requiredLevel,
            @Nullable Inner inner,
            Inner requiredInner,
            @JsonProperty("renamed_note") @Nullable String note) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Sparse(
            String code,
            @Nullable String detail,

            @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable
            String shown) {}

    record PartlyOmitted(
            String always,

            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
            String omitted) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Explicit(String value, @Nullable String other) {}

    private final Schema<?> sample = schema(Sample.class);

    @Test
    void allComponentsAreRequired() {
        assertThat(sample.getRequired())
                .containsExactlyInAnyOrder(
                        "required", "text", "level", "requiredLevel", "inner", "requiredInner", "renamed_note");
        assertThat(schema(Explicit.class).getRequired()).containsExactlyInAnyOrder("value", "other");
    }

    /**
     * Запись, которая опускает пустое, {@code null} не пишет: поле без значения отсутствует, поэтому оно не обязательно
     * и {@code null} не допускает (тип фронта — {@code detail?: string}). Своя {@code ALWAYS} у поля возвращает
     * {@code null}.
     */
    @Test
    void recordWithoutAlwaysInclusionIsLeftAsIs() {
        var sparse = schema(Sparse.class);

        assertThat(sparse.getRequired()).isNullOrEmpty();
        Schema<?> detail = sparse.getProperties().get("detail");
        assertThat(detail.getTypes()).containsExactly("string");
        Schema<?> shown = sparse.getProperties().get("shown");
        assertThat(shown.getTypes()).containsExactlyInAnyOrder("string", "null");
    }

    /** Поле со своим {@code NON_NULL} опускается вместо {@code null}: не обязательно и {@code null} не допускает. */
    @Test
    void componentWithOwnInclusionIsOptional() {
        var partlyOmitted = schema(PartlyOmitted.class);

        assertThat(partlyOmitted.getRequired()).containsExactly("always");
        Schema<?> omitted = partlyOmitted.getProperties().get("omitted");
        assertThat(omitted.getTypes()).containsExactly("string");
    }

    @Test
    void nullableScalarGetsNullType() {
        assertThat(property("text").getTypes()).containsExactlyInAnyOrder("string", "null");
        assertThat(property("required").getTypes()).containsExactly("string");
    }

    @Test
    void nullableEnumAcceptsNull() {
        assertThat(property("level").getTypes()).containsExactlyInAnyOrder("string", "null");
        assertThat(enumValues("level")).containsExactlyInAnyOrder("READ", "WRITE", null);
        assertThat(enumValues("requiredLevel")).containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void nullableReferenceIsOneOfWithNull() {
        Schema<?> inner = property("inner");
        assertThat(inner.get$ref()).isNull();
        assertThat(inner.getOneOf()).hasSize(2);
        Schema<?> reference = inner.getOneOf().get(0);
        Schema<?> nothing = inner.getOneOf().get(1);
        assertThat(reference.get$ref()).isEqualTo("#/components/schemas/Inner");
        assertThat(nothing.getTypes()).containsExactly("null");
        assertThat(property("requiredInner").get$ref()).isEqualTo("#/components/schemas/Inner");
    }

    @Test
    void jsonPropertyNameIsUsed() {
        assertThat(property("renamed_note").getTypes()).containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void annotatedTypeWithoutJavaTypeIsPassedThrough() {
        var resolved = new Schema<>().$ref("#/components/schemas/Inner");
        ModelConverter next = (type, context, chain) -> resolved;

        var schema = new NullableRecordComponents()
                .resolve(
                        new AnnotatedType(),
                        new ModelConverterContextImpl(next),
                        List.of(next).iterator());

        assertThat(schema).isSameAs(resolved);
    }

    private Schema<?> property(String name) {
        return sample.getProperties().get(name);
    }

    private List<Object> enumValues(String name) {
        return new ArrayList<>(property(name).getEnum());
    }

    private static Schema<?> schema(Class<? extends Record> type) {
        var converters = new ModelConverters(true);
        converters.addConverter(new NullableRecordComponents());
        return converters.readAll(type).get(type.getSimpleName());
    }
}
