package gag.nails.notifications.internal.maintenance;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.JobsLifecycle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Component;

/**
 * Незавершённые публикации реестра событий Spring Modulith — события, которые слушатели модуля уведомлений не
 * обработали. Повтор после рестарта ({@code republish-outstanding-events-on-restart}) их подбирает, но без рестарта
 * один сбой (база недоступна, взаимная блокировка) ждал бы его сколько угодно, поэтому неудачные ({@code FAILED})
 * повторяются по расписанию. Повтор безопасен: ключи повтора сообщений не дают дублей. Таблица реестра — в схеме из
 * {@code spring.modulith.events.jdbc.schema}, как у самого реестра.
 */
@Component
public class EventPublicationRetries {

    /** Неудачная публикация повторяется впервые не раньше, чем через это время после публикации. */
    public static final Duration MIN_AGE = Duration.ofMinutes(5);

    /** Самая длинная пауза между повторами одной публикации. */
    public static final Duration MAX_BACKOFF = Duration.ofHours(6);

    /** Сколько неудачных публикаций повторяется за один запуск. */
    static final int BATCH = 100;

    /**
     * Сколько неудачных публикаций читается за один запуск. Spring Modulith ограничивает выборку
     * ({@code ORDER BY publication_date LIMIT}) до фильтра, поэтому окно шире пачки: иначе сотня старых публикаций,
     * ждущих своей паузы, закрывала бы более новые, которым повтор уже пора.
     */
    static final int WINDOW = 1000;

    /** Публикация, незавершённая дольше этого, — повод для WARN: повторы с ней не справляются. */
    public static final Duration STALE_AFTER = Duration.ofDays(1);

    private static final Pattern SCHEMA = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Logger log = LoggerFactory.getLogger(EventPublicationRetries.class);

    private final FailedEventPublications failed;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final JobsLifecycle lifecycle;
    private final String table;

    EventPublicationRetries(
            FailedEventPublications failed,
            JdbcClient jdbc,
            Clock clock,
            JobsLifecycle lifecycle,
            @Value("${spring.modulith.events.jdbc.schema:}") String schema) {
        this.failed = failed;
        this.jdbc = jdbc;
        this.clock = clock;
        this.lifecycle = lifecycle;
        if (!schema.isEmpty() && !SCHEMA.matcher(schema).matches()) {
            throw new InternalException("spring.modulith.events.jdbc.schema is not a plain identifier: " + schema);
        }
        this.table = schema.isEmpty() ? "event_publication" : schema + ".event_publication";
    }

    /**
     * Повторяет до {@value #BATCH} неудачных публикаций из {@value #WINDOW} самых старых, старые первыми: каждую —
     * заново её слушателю (асинхронно, как обычную). Публикации в работе не трогает. Повтор — не раньше
     * {@link #MIN_AGE} после публикации, а после повтора, который снова не удался, — с растущей паузой
     * ({@link #backoff}), чтобы публикация, на которой слушатель падает всегда, не повторялась каждые 5 минут вечно.
     *
     * <p>Возраст проверяется фильтром: в Spring Modulith 2.1.1 запрос неудачных публикаций
     * ({@code JdbcEventPublicationRepositoryV2.findFailedPublications}) склеивает условие на дату через {@code AND} с
     * {@code STATUS = 'FAILED' OR (...)} без скобок, и для {@code FAILED} минимальный возраст запроса не действует.
     *
     * <p>При остановке приложения ({@link JobsLifecycle}) не повторяет ничего, а начатый запуск — больше ни одной
     * публикации: пул слушателей уже дорабатывает своё.
     */
    public void resubmitFailed() {
        if (!lifecycle.accepting()) {
            return;
        }
        var due = dueAtMost(clock.instant(), BATCH);
        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(MIN_AGE)
                .withBatchSize(WINDOW)
                .withFilter(publication -> lifecycle.accepting() && due.test(publication)));
    }

    /**
     * Фильтр запуска: публикации, которым повтор пора ({@link #due}), — не больше {@code limit}, по порядку выборки;
     * ждущие паузы не занимают места в пачке. Счётчик — на один запуск: фильтр вызывается по разу на публикацию.
     */
    static Predicate<EventPublication> dueAtMost(Instant now, int limit) {
        var left = new AtomicInteger(limit);
        return publication -> due(publication, now) && left.getAndDecrement() > 0;
    }

    /**
     * Пауза перед следующим повтором после {@code resubmissions} уже сделанных: 5 минут, затем вдвое больше каждый
     * раз, но не больше {@link #MAX_BACKOFF}.
     */
    public static Duration backoff(int resubmissions) {
        var doublings = Math.clamp(resubmissions - 1L, 0, 16);
        var pause = MIN_AGE.multipliedBy(1L << doublings);
        return pause.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : pause;
    }

    /** Пора ли повторять публикацию: первый раз — по возрасту, дальше — по паузе после прошлого повтора. */
    static boolean due(EventPublication publication, Instant now) {
        var lastResubmission = publication.getLastResubmissionDate();
        if (lastResubmission == null) {
            return !publication.getPublicationDate().plus(MIN_AGE).isAfter(now);
        }
        return !lastResubmission
                .plus(backoff(publication.getCompletionAttempts()))
                .isAfter(now);
    }

    /**
     * Пишет WARN, если в реестре есть публикации, незавершённые дольше {@link #STALE_AFTER}: слушатель падает на них
     * раз за разом или они зависли в работе, — их нужно разобрать вручную. В лог — только число.
     *
     * @return сколько таких публикаций
     */
    public long warnAboutStale() {
        var before = clock.instant().minus(STALE_AFTER).atOffset(ZoneOffset.UTC);
        var stale = jdbc.sql("select count(*) from " + table
                        + " where completion_date is null and publication_date < :before")
                .param("before", before)
                .query(Long.class)
                .single();
        if (stale > 0) {
            log.warn("{} event publications have been incomplete for more than {}", stale, STALE_AFTER);
        }
        return stale;
    }
}
