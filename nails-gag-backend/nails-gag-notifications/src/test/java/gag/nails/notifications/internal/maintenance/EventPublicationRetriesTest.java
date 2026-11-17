package gag.nails.notifications.internal.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

/**
 * Повтор неудачной публикации: впервые — не раньше 5 минут после публикации, дальше — с паузой, растущей вдвое после
 * каждого неудачного повтора, до 6 часов: слушатель, падающий на публикации всегда, не дёргается каждые 5 минут.
 */
class EventPublicationRetriesTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-01T06:00:00Z");

    @Test
    void backoffDoublesUpToSixHours() {
        assertThat(EventPublicationRetries.backoff(0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(EventPublicationRetries.backoff(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(EventPublicationRetries.backoff(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(EventPublicationRetries.backoff(4)).isEqualTo(Duration.ofMinutes(40));
        assertThat(EventPublicationRetries.backoff(8)).isEqualTo(Duration.ofHours(6));
        assertThat(EventPublicationRetries.backoff(1000)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void firstResubmissionWaitsForMinimalAge() {
        var publication = publication(null, 1);

        assertThat(EventPublicationRetries.due(publication, PUBLISHED.plus(Duration.ofMinutes(4))))
                .isFalse();
        assertThat(EventPublicationRetries.due(publication, PUBLISHED.plus(Duration.ofMinutes(5))))
                .isTrue();
    }

    @Test
    void repeatedFailuresWaitLonger() {
        var lastResubmission = PUBLISHED.plus(Duration.ofHours(1));
        var publication = publication(lastResubmission, 4);

        assertThat(EventPublicationRetries.due(publication, lastResubmission.plus(Duration.ofMinutes(39))))
                .isFalse();
        assertThat(EventPublicationRetries.due(publication, lastResubmission.plus(Duration.ofMinutes(40))))
                .isTrue();
    }

    private static EventPublication publication(@Nullable Instant lastResubmission, int attempts) {
        return new EventPublication() {

            @Override
            public UUID getIdentifier() {
                return UUID.randomUUID();
            }

            @Override
            public Object getEvent() {
                return "event";
            }

            @Override
            public Instant getPublicationDate() {
                return PUBLISHED;
            }

            @Override
            public Optional<Instant> getCompletionDate() {
                return Optional.empty();
            }

            @Override
            public Status getStatus() {
                return Status.FAILED;
            }

            @Override
            public @Nullable Instant getLastResubmissionDate() {
                return lastResubmission;
            }

            @Override
            public int getCompletionAttempts() {
                return attempts;
            }
        };
    }
}
