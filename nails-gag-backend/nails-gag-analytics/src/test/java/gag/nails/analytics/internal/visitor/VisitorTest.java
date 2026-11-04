package gag.nails.analytics.internal.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Адрес посетителя: только IP-литерал, в каноническом виде. Без Spring и базы. */
class VisitorTest {

    private static final String UA = "Mozilla/5.0";

    @ParameterizedTest
    @CsvSource({
        "2001:db8::1, 2001:0db8:0:0::1",
        "2001:db8::1, 2001:DB8:0:0:0:0:0:1",
        "2001:db8::1, [2001:db8::1]",
        "::ffff:203.0.113.7, 203.0.113.7",
        "::FFFF:cb00:7107, 203.0.113.7"
    })
    void sameAddressWrittenDifferentlyIsOneVisitor(String one, String another) {
        assertThat(Visitor.of(one, UA)).isPresent().isEqualTo(Visitor.of(another, UA));
    }

    @ParameterizedTest
    @CsvSource({"203.0.113.7, 4", "::ffff:203.0.113.7, 4", "2001:db8::1, 16", "::1, 16", "::203.0.113.7, 16"})
    void addressBytes(String ip, int length) {
        assertThat(Visitor.of(ip, UA).orElseThrow().address()).hasSize(length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "example.com", "localhost", "203.0.113.7:443", "2001:db8::g", "1.2.3.256"})
    void nonLiteralIsRejected(String ip) {
        assertThat(Visitor.of(ip, UA)).isEmpty();
    }

    @Test
    void toStringHidesValues() {
        assertThat(Visitor.of("203.0.113.7", UA).orElseThrow().toString())
                .doesNotContain("203")
                .doesNotContain(UA);
    }
}
