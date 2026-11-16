package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingConfirmed;
import gag.nails.notifications.internal.maintenance.EventPublicationRetries;
import gag.nails.notifications.model.MessageKind;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Неудачные публикации реестра повторяются по расписанию, без рестарта: публикация старше пяти минут уходит слушателю
 * заново, более свежая ждёт. Сбой слушателя — триггер на вставку сообщений своей записи, как в
 * {@code OutstandingEventsTest}. Часы реестра — часы тестов.
 */
@NotificationsModuleTest
class FailedPublicationsRetryTest extends QueueTestSupport {

    @Autowired
    EventPublicationRetries retries;

    private @Nullable String failureTrigger;

    @AfterEach
    void dropTrigger() {
        if (failureTrigger == null) {
            return;
        }
        jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON notifications.message".formatted(failureTrigger))
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS notifications.%s()".formatted(failureTrigger))
                .update();
        failureTrigger = null;
    }

    @Test
    void failedPublicationIsResubmittedOnceOldEnough() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();
        var occurredAt = clock.instant();
        failInsertsOf(bookingId);
        transaction.executeWithoutResult(status ->
                publisher.publishEvent(new BookingConfirmed(bookingId, clientId, occurredAt, false, occurredAt)));
        awaitFailed(bookingId);
        dropTrigger();

        clock.advance(EventPublicationRetries.MIN_AGE.minusSeconds(1));
        retries.resubmitFailed();

        assertThat(publicationStatuses(bookingId)).containsExactly(FAILED);

        clock.advance(Duration.ofMinutes(1));
        retries.resubmitFailed();
        awaitProcessed(bookingId);

        assertThat(messages(bookingId)).containsExactly(toClient(MessageKind.CONFIRMED, clientId, occurredAt));
    }

    @Test
    void publicationsIncompleteForMoreThanADayAreCounted() {
        var bookingId = bookingId();
        failInsertsOf(bookingId);
        var occurredAt = clock.instant();
        transaction.executeWithoutResult(status -> publisher.publishEvent(
                new BookingConfirmed(bookingId, UUID.randomUUID(), occurredAt, false, occurredAt)));
        awaitFailed(bookingId);
        var before = retries.warnAboutStale();

        clock.advance(EventPublicationRetries.STALE_AFTER.plusSeconds(1));

        assertThat(retries.warnAboutStale()).isGreaterThanOrEqualTo(before + 1);
    }

    private void failInsertsOf(UUID bookingId) {
        var trigger = "failed_publications_test_" + bookingId.toString().replace("-", "");
        failureTrigger = trigger;
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION notifications.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.booking_id = '%s' THEN
                        RAISE EXCEPTION 'message rejected by test';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(trigger, bookingId)).update();
        jdbc.sql("""
                CREATE OR REPLACE TRIGGER %1$s BEFORE INSERT ON notifications.message
                FOR EACH ROW EXECUTE FUNCTION notifications.%1$s()""".formatted(trigger)).update();
    }

    private void awaitFailed(UUID bookingId) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!publicationStatuses(bookingId).equals(List.of(FAILED))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "publication of " + bookingId + " has not failed: " + publicationStatuses(bookingId));
            }
            pause();
        }
    }
}
