package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Очередь хранит момент события сообщения ({@code occurred_at}) — по нему отправка упорядочивает сообщения с равным
 * сроком: у событийного — момент события (а не срок после переноса из тихих часов), у напоминания — момент расчёта.
 */
@NotificationsModuleTest
class MessageMomentTest extends QueueTestSupport {

    @Test
    void messagesKeepTheMomentOfTheirEvent() {
        var lateEvening = Instant.parse("2026-10-01T20:00:00Z");
        clock.set(lateEvening);
        var booking = bookByMaster(client("Анна"), slot(MONDAY, "14:00"), false);

        assertThat(moments(booking.id(), "BOOKED_BY_MASTER"))
                .isEqualTo(new Moments(Instant.parse("2026-10-02T06:00:00Z"), lateEvening));
        assertThat(moments(booking.id(), "REMINDER_DAY_BEFORE").occurredAt()).isEqualTo(lateEvening);
    }

    private Moments moments(UUID bookingId, String kind) {
        return jdbc.sql("select due_at, occurred_at from notifications.message where booking_id = ? and kind = ?")
                .params(bookingId, kind)
                .query((row, rowNumber) -> new Moments(
                        row.getTimestamp("due_at").toInstant(),
                        row.getTimestamp("occurred_at").toInstant()))
                .single();
    }

    private record Moments(Instant dueAt, Instant occurredAt) {}
}
