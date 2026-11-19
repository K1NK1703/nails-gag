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

    /**
     * Выборка Spring Modulith упорядочена по дате и ограничена до фильтра: окно шире пачки, а фильтр пропускает только
     * публикации, которым повтор пора, и не больше пачки — старые, ждущие паузы, не закрывают новую.
     */
    @Test
    void publicationsWaitingForBackoffDoNotTakeBatchPlaces() {
        var now = PUBLISHED.plus(Duration.ofHours(2));
        var waiting = publication(now.minus(Duration.ofMinutes(1)), 3);
        var filter = EventPublicationRetries.dueAtMost(now, EventPublicationRetries.BATCH);

        for (var i = 0; i < 150; i++) {
            assertThat(filter.test(waiting)).isFalse();
        }
        assertThat(filter.test(publication(null, 1))).isTrue();
        assertThat(EventPublicationRetries.WINDOW).isGreaterThan(EventPublicationRetries.BATCH);
    }

    @Test
    void atMostOneBatchIsResubmittedPerRun() {
        var due = publication(null, 1);
        var filter = EventPublicationRetries.dueAtMost(PUBLISHED.plus(Duration.ofHours(1)), 100);

        var passed = 0;
        for (var i = 0; i < 150; i++) {
            if (filter.test(due)) {
                passed++;
            }
        }

        assertThat(passed).isEqualTo(100);
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
