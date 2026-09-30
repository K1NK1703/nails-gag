package gag.nails.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExceptionsTest {

    @Test
    void userFacingExceptionCarriesCodeAndImmutableParams() {
        var e = new ValidationException("phone", "invalid");

        assertThat(e.code()).isEqualTo("common.validation");
        assertThat(e.params()).containsExactlyInAnyOrderEntriesOf(Map.of("field", "phone", "reason", "invalid"));
        assertThat(e.getMessage()).startsWith("common.validation");
    }

    @Test
    void internalExceptionKeepsCause() {
        var cause = new IllegalStateException("driver failed");
        var e = new InternalException("cannot read slot", cause);

        assertThat(e.getMessage()).isEqualTo("cannot read slot");
        assertThat(e.getCause()).isSameAs(cause);
    }
}
