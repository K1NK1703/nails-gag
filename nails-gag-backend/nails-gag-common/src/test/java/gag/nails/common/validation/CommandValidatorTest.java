package gag.nails.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

class CommandValidatorTest {

    record Command(@NotBlank String title, @Max(10) int count) {}

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
}
