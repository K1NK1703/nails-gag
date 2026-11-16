package gag.nails.notifications.internal.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Component;

/**
 * Незавершённые публикации реестра событий Spring Modulith — события, которые слушатели модуля уведомлений не
 * обработали. Повтор после рестарта ({@code republish-outstanding-events-on-restart}) их подбирает, но без рестарта
 * один сбой (база недоступна, взаимная блокировка) ждал бы его сколько угодно, поэтому неудачные ({@code FAILED})
 * повторяются по расписанию. Повтор безопасен: ключи повтора сообщений не дают дублей.
 */
@Component
public class EventPublicationRetries {

    /** Повторяются неудачные публикации не моложе этого: только что упавший слушатель не дёргается сразу. */
    public static final Duration MIN_AGE = Duration.ofMinutes(5);

    /** Сколько неудачных публикаций за один повтор. */
    static final int BATCH = 100;

    /** Публикация, незавершённая дольше этого, — повод для WARN: повторы с ней не справляются. */
    public static final Duration STALE_AFTER = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(EventPublicationRetries.class);

    private final FailedEventPublications failed;
    private final JdbcClient jdbc;
    private final Clock clock;

    EventPublicationRetries(FailedEventPublications failed, JdbcClient jdbc, Clock clock) {
        this.failed = failed;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Повторяет до {@value #BATCH} неудачных публикаций старше {@link #MIN_AGE}, старые первыми: каждую — заново её
     * слушателю (асинхронно, как обычную). Публикации в работе не трогает. Слушатель снова упал — публикация снова
     * {@code FAILED} и повторится следующим запуском.
     *
     * <p>Возраст проверяется и фильтром: в Spring Modulith 2.1.1 запрос неудачных публикаций
     * ({@code JdbcEventPublicationRepositoryV2.findFailedPublications}) склеивает условие на дату через {@code AND} с
     * {@code STATUS = 'FAILED' OR (...)} без скобок, и для {@code FAILED} минимальный возраст не действует.
     */
    public void resubmitFailed() {
        var before = clock.instant().minus(MIN_AGE);
        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(MIN_AGE)
                .withBatchSize(BATCH)
                .withFilter(publication -> publication.getPublicationDate().isBefore(before)));
    }

    /**
     * Пишет WARN, если в реестре есть публикации, незавершённые дольше {@link #STALE_AFTER}: слушатель падает на них
     * раз за разом или они зависли в работе, — их нужно разобрать вручную. В лог — только число.
     *
     * @return сколько таких публикаций
     */
    public long warnAboutStale() {
        var before = clock.instant().minus(STALE_AFTER).atOffset(ZoneOffset.UTC);
        var stale = count(before);
        if (stale > 0) {
            log.warn("{} event publications have been incomplete for more than {}", stale, STALE_AFTER);
        }
        return stale;
    }

    private long count(OffsetDateTime before) {
        return jdbc.sql("""
                        select count(*) from modulith.event_publication
                        where completion_date is null and publication_date < :before
                        """).param("before", before).query(Long.class).single();
    }
}
