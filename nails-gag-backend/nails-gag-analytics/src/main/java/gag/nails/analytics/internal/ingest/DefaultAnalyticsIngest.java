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
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
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
 * <p>Приём не должен занимать общий пул соединений: одновременно пишется не больше
 * {@code nails-gag.analytics.max-concurrent-writes} событий, лишнее отбрасывается без ожидания; у транзакции короткие
 * пределы ожидания блокировок и запросов ({@link EventCounter}). Уровень изоляции задан явно — {@code READ COMMITTED}:
 * на нём держится общая соль дня при одновременных первых событиях ({@code DaySalts}).
 *
 * <p>Сбой ловится снаружи транзакции: к этому моменту {@link TransactionTemplate} её уже откатил, частично
 * записанных счётчиков не остаётся. Потери пишутся в лог с ограничением частоты ({@link LossLog}). IP и
 * {@code User-Agent} не уходят ни в базу, ни в лог; в базе — только отпечаток (хэш).
 */
@Service
class DefaultAnalyticsIngest implements AnalyticsIngest {

    /** Места записи; пакетный доступ — для теста, который занимает их все. */
    final Semaphore writes;

    private final EventCounter counter;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SalonTimeZone salonTimeZone;
    private final String ownHost;
    private final LossLog losses;

    DefaultAnalyticsIngest(
            EventCounter counter,
            PlatformTransactionManager transactionManager,
            Clock clock,
            SalonTimeZone salonTimeZone,
            AnalyticsProperties properties) {
        this.writes = new Semaphore(properties.maxConcurrentWrites());
        this.counter = counter;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
        this.salonTimeZone = salonTimeZone;
        this.ownHost = EventKeys.ownHost(properties.siteHost());
        this.losses = new LossLog(clock);
    }

    @Override
    public void record(RecordEvent event, String ip, @Nullable String userAgent) {
        Optional<AcceptedEvent> accepted;
        try {
            accepted = accept(event, ip, userAgent);
        } catch (RuntimeException e) {
            losses.failed("event check failed", e);
            return;
        }
        accepted.ifPresent(this::write);
    }

    private void write(AcceptedEvent event) {
        if (!writes.tryAcquire()) {
            losses.busy();
            return;
        }
        try {
            var day = salonTimeZone.today(clock);
            transaction.executeWithoutResult(status -> counter.count(day, event));
            losses.flush();
        } catch (RuntimeException e) {
            losses.failed(event.tallies().getFirst().metric() + " event was not written", e);
        } finally {
            writes.release();
        }
    }

    /** Робот, не IP-литерал или неверное событие — пусто. */
    private Optional<AcceptedEvent> accept(RecordEvent event, String ip, @Nullable String userAgent) {
        if (userAgent == null || Robots.isRobot(userAgent)) {
            return Optional.empty();
        }
        var visitor = Visitor.of(ip, userAgent);
        if (visitor.isEmpty()) {
            return Optional.empty();
        }
        return EventKeys.tally(event).map(tally -> {
            if (tally.metric() != Metric.PAGE_VIEW) {
                return new AcceptedEvent(List.of(tally), null);
            }
            if (!Boolean.TRUE.equals(event.entry())) {
                return new AcceptedEvent(List.of(tally), visitor.get());
            }
            var source = new Tally(Metric.SOURCE, EventKeys.source(event.referrer(), ownHost));
            var device = new Tally(Metric.DEVICE, Devices.of(userAgent));
            return new AcceptedEvent(List.of(tally, source, device), visitor.get());
        });
    }
}
