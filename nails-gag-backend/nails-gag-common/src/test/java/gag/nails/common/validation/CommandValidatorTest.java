package gag.nails.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

class CommandValidatorTest {

    record Command(@NotBlank String title, @Max(10) int count) {}

    record Inner(@NotBlank String value) {}

    record Outer(@Valid Inner inner) {}

    private final CommandValidator validator =
            new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void returnsValidCommandUnchanged() {
        var command = new Command("ok", 3);

        assertThat(validator.requireValid(command)).isSameAs(command);
    }

    @Test
    void reportsFirstViolationByFieldNameAsValidationException() {
        assertThatThrownBy(() -> validator.requireValid(new Command(" ", 99)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> {
                    var params = ((ValidationException) e).params();
                    // поля сортируются по имени: count раньше title
                    assertThat(params).containsEntry("field", "count");
                    // текст зависит от локали JVM, важно лишь, что причина передана
                    assertThat(params.get("reason")).asString().isNotBlank();
                });
    }

    @Test
    void reportsNestedViolationWithFullPath() {
        assertThatThrownBy(() -> validator.requireValid(new Outer(new Inner(" "))))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> {
                    var params = ((ValidationException) e).params();
                    assertThat(params).containsEntry("field", "inner.value");
                    assertThat(params.get("reason")).asString().isNotBlank();
                });
    }
}
