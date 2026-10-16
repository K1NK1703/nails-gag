package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** {@code null} в схеме OpenAPI 3.1 по {@code @Nullable} компонентов записи — без контекста, на своём реестре. */
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

    private final Schema<?> sample = sample();

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

    private Schema<?> property(String name) {
        return sample.getProperties().get(name);
    }

    private List<Object> enumValues(String name) {
        return new ArrayList<>(property(name).getEnum());
    }

    private static Schema<?> sample() {
        var converters = new ModelConverters(true);
        converters.addConverter(new NullableRecordComponents());
        return converters.readAll(Sample.class).get("Sample");
    }
}
