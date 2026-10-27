package gag.nails.clients.internal.consent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.error.InternalException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsentEntityTest {

    @Test
    void withdrawnConsentCannotBeWithdrawnAgain() {
        var givenAt = Instant.parse("2026-10-01T06:00:00Z");
        var consent = new ConsentEntity(
                UUID.randomUUID(), ConsentKind.PHOTO_PUBLICATION, "2026-09-25", ConsentSource.SITE_CHECKBOX, givenAt);
        consent.withdraw(givenAt.plusSeconds(60));

        assertThatThrownBy(() -> consent.withdraw(givenAt.plusSeconds(120))).isInstanceOf(InternalException.class);
    }
}
