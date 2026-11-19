package gag.nails.notifications.internal.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.model.MessageKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Несогласованное сообщение не доходит до базы: вид и получатель, сроки, ключ повтора. */
class NewMessageTest {

    private static final Instant DUE = Instant.parse("2026-11-20T09:00:00Z");
    private static final Instant EXPIRES = DUE.plus(Duration.ofDays(1));

    private final Recipient client = new Recipient.Client(UUID.randomUUID());
    private final Recipient account = new Recipient.Account(UUID.randomUUID());

    @Test
    void staffKindGoesToAccountAndClientKindToCard() {
        assertThatCode(() -> message(MessageKind.STAFF_NEW_BOOKING, account, EXPIRES, "key"))
                .doesNotThrowAnyException();
        assertThatCode(() -> message(MessageKind.CONFIRMED, client, EXPIRES, "key"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> message(MessageKind.STAFF_NEW_BOOKING, client, EXPIRES, "key"))
                .isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> message(MessageKind.CONFIRMED, account, EXPIRES, "key"))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void messageExpiresAfterItIsDue() {
        assertThatThrownBy(() -> message(MessageKind.CONFIRMED, client, DUE, "key"))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void dedupKeyFitsItsColumn() {
        assertThatCode(() -> message(MessageKind.CONFIRMED, client, EXPIRES, "k".repeat(255)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> message(MessageKind.CONFIRMED, client, EXPIRES, "k".repeat(256)))
                .isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> message(MessageKind.CONFIRMED, client, EXPIRES, ""))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void recipientKeyNamesItsKind() {
        var id = UUID.fromString("019a8600-0000-7000-8000-000000000001");

        assertThat(new Recipient.Client(id).key()).isEqualTo("client:019a8600-0000-7000-8000-000000000001");
        assertThat(new Recipient.Account(id).key()).isEqualTo("account:019a8600-0000-7000-8000-000000000001");
    }

    private NewMessage message(MessageKind kind, Recipient recipient, Instant expiresAt, String dedupKey) {
        return new NewMessage(kind, recipient, UUID.randomUUID(), DUE, expiresAt, dedupKey, DUE);
    }
}
