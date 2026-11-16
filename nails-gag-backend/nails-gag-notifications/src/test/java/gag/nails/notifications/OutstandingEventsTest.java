package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingConfirmed;
import gag.nails.notifications.model.MessageKind;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;

/**
 * Реестр публикаций: событие, которое слушатель не обработал (упал), остаётся в реестре незавершённым и после
 * повторной доставки ставит сообщения в очередь. После рестарта приложение повторяет незавершённые публикации тем же
 * вызовом {@link IncompleteEventPublications#resubmitIncompletePublications} ({@code
 * republish-outstanding-events-on-restart}); в тестах повтор при старте выключен, и тест вызывает его сам — только для
 * своей публикации. Сбой слушателя — триггер на вставку сообщений своей записи.
 */
@NotificationsModuleTest
class OutstandingEventsTest extends QueueTestSupport {

    /** Имя триггера и его функции в схеме notifications: ставится тестом и снимается после него. */
    private static final String FAILURE_TRIGGER = "outstanding_events_test_fail_insert";

    private static final Instant NOW = TestTimeConfiguration.START;

    @Autowired
    IncompleteEventPublications incompletePublications;

    @AfterEach
    void dropTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON notifications.message".formatted(FAILURE_TRIGGER))
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS notifications.%s()".formatted(FAILURE_TRIGGER))
                .update();
    }

    @Test
    void outstandingEventIsProcessedAfterRestart() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();
        failInsertsOf(bookingId);

        transaction.executeWithoutResult(
                status -> publisher.publishEvent(new BookingConfirmed(bookingId, clientId, NOW, false, NOW)));
        awaitFailed(bookingId);

        assertThat(messages(bookingId)).isEmpty();

        dropTrigger();
        incompletePublications.resubmitIncompletePublications(
                publication -> publication.getEvent() instanceof BookingConfirmed event
                        && event.bookingId().equals(bookingId));
        awaitProcessed(bookingId);

        assertThat(messages(bookingId)).containsExactly(toClient(MessageKind.CONFIRMED, clientId, NOW));
    }

    private void failInsertsOf(UUID bookingId) {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION notifications.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.booking_id = '%s' THEN
                        RAISE EXCEPTION 'message rejected by test';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(FAILURE_TRIGGER, bookingId)).update();
        jdbc.sql("""
                CREATE OR REPLACE TRIGGER %1$s BEFORE INSERT ON notifications.message
                FOR EACH ROW EXECUTE FUNCTION notifications.%1$s()""".formatted(FAILURE_TRIGGER)).update();
    }

    /** Ждёт, пока публикация о записи не будет отмечена неудачной: слушатель упал, публикация осталась. */
    private void awaitFailed(UUID bookingId) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!publicationStatuses(bookingId).equals(List.of("FAILED"))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "publication of " + bookingId + " has not failed: " + publicationStatuses(bookingId));
            }
            pause();
        }
    }
}
