package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.AnalyticsIngest;
import gag.nails.analytics.command.RecordEvent;
import gag.nails.analytics.internal.AnalyticsProperties;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.internal.visitor.Visitor;
import gag.nails.analytics.model.Metric;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Приём события: проверка и ключи — без базы, запись — в своей транзакции ({@code REQUIRES_NEW}). Своя, а не
 * присоединение к транзакции вызывающего: ошибка базы прерывает транзакцию PostgreSQL целиком, и присоединённый приём
 * сломал бы чужую работу, хотя наружу ничего не бросает; запрет вызова из транзакции тоже пришлось бы молча глотать.
 * Цена — второе соединение, пока вызывающий держит своё; HTTP-адрес приёма вызывает без транзакции.
 *
 * <p>Сбой ловится снаружи транзакции: к этому моменту {@link TransactionTemplate} её уже откатил, частично
 * записанных счётчиков не остаётся. В лог пишется только метрика события и исключение — ни IP, ни
 * {@code User-Agent}, ни отпечаток в базу или лог не уходят (в базе — только хэш).
 */
@Service
class DefaultAnalyticsIngest implements AnalyticsIngest {

    private static final Logger log = LoggerFactory.getLogger(DefaultAnalyticsIngest.class);

    private final EventCounter counter;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SalonTimeZone salonTimeZone;
    private final String siteHost;

    DefaultAnalyticsIngest(
            EventCounter counter,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SalonTimeZone salonTimeZone,
            AnalyticsProperties properties) {
        this.counter = counter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.salonTimeZone = salonTimeZone;
        this.siteHost = properties.siteHost();
    }

    @Override
    public void record(RecordEvent event, String ip, @Nullable String userAgent) {
        Optional<AcceptedEvent> accepted;
        try {
            accepted = accept(event, ip, userAgent);
        } catch (RuntimeException e) {
            log.error("analytics event check failed", e);
            return;
        }
        accepted.ifPresent(this::write);
    }

    private void write(AcceptedEvent event) {
        try {
            var day = salonTimeZone.today(clock);
            transaction.executeWithoutResult(status -> counter.count(day, event));
        } catch (RuntimeException e) {
            log.error(
                    "analytics {} event was not recorded",
                    event.tallies().getFirst().metric(),
                    e);
        }
    }

    private Optional<AcceptedEvent> accept(RecordEvent event, String ip, @Nullable String userAgent) {
        if (userAgent == null || Robots.isRobot(userAgent)) {
            return Optional.empty();
        }
        return EventKeys.tally(event).map(tally -> {
            if (tally.metric() != Metric.PAGE_VIEW) {
                return new AcceptedEvent(List.of(tally), null);
            }
            var visitor = new Visitor(ip, userAgent);
            if (!Boolean.TRUE.equals(event.entry())) {
                return new AcceptedEvent(List.of(tally), visitor);
            }
            var source = new Tally(Metric.SOURCE, EventKeys.source(event.referrer(), siteHost));
            var device = new Tally(Metric.DEVICE, Devices.of(userAgent));
            return new AcceptedEvent(List.of(tally, source, device), visitor);
        });
    }
}
