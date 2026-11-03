package gag.nails.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void versionVariantAndTimestamp() {
        var millis = 1_790_000_000_123L;

        var id = UuidV7.at(millis);

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
    }

    @Test
    void laterMillisecondSortsLater() {
        var millis = 1_790_000_000_123L;

        for (var i = 0; i < 1000; i++) {
            var earlier = UuidV7.at(millis);
            var later = UuidV7.at(millis + 1);
            assertThat(earlier).isLessThan(later);
            // Байтовый порядок PostgreSQL совпадает с порядком строк.
            assertThat(earlier.toString()).isLessThan(later.toString());
        }
    }

    @Test
    void currentIdsAreDistinctAndCarryTheCurrentTime() {
        var before = System.currentTimeMillis();
        var ids = new HashSet<UUID>();

        for (var i = 0; i < 10_000; i++) {
            ids.add(UuidV7.next());
        }

        var after = System.currentTimeMillis();
        assertThat(ids).hasSize(10_000).allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
        });
    }
}
