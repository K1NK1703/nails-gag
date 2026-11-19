package gag.nails.notifications.internal.maintenance;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.NotificationMaintenance;
import gag.nails.notifications.internal.NotificationsProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Два {@code DELETE} в своей транзакции. Сообщения: только в конечном состоянии и без доставок в работе — их строки и
 * строки их доставок не трогает никто, кроме уборки (отправка блокирует только {@code PENDING}-сообщения и
 * {@code PENDING}-доставки, слушатели событий — только {@code PENDING}-сообщения), поэтому уборка никого не ждёт и
 * никого не держит; доставки удаляются каскадом. Доставка не возвращается в {@code PENDING}, так что сообщение,
 * прошедшее проверку «нет доставок в работе», в работу уже не вернётся. Токены привязки — истёкшие: их строки нужны
 * только привязке по ещё живой ссылке. В лог — только числа.
 */
@Service
class DefaultNotificationMaintenance implements NotificationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(DefaultNotificationMaintenance.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final NotificationsProperties properties;
    private final Clock clock;

    DefaultNotificationMaintenance(
            JdbcClient jdbc, TransactionTemplate transaction, NotificationsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int purgeExpired() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("notifications purge must run outside a transaction");
        }
        var now = clock.instant();
        var before = now.minus(Duration.ofDays(properties.retentionDays()));
        var removed = Objects.requireNonNull(transaction.execute(status -> purge(before, now)));
        if (removed.messages() > 0 || removed.tokens() > 0) {
            log.info(
                    "notifications purge removed {} messages and {} expired link tokens",
                    removed.messages(),
                    removed.tokens());
        }
        return removed.messages();
    }

    private Removed purge(Instant before, Instant now) {
        var messages = jdbc.sql("""
                        delete from notifications.message m
                        where m.status in ('DISPATCHED', 'CANCELLED') and m.due_at < :before
                          and not exists (select 1 from notifications.delivery d
                              where d.message_id = m.id and d.status = 'PENDING')
                        """).param("before", timestamp(before)).update();
        var tokens = jdbc.sql("delete from notifications.link_token where expires_at <= :now")
                .param("now", timestamp(now))
                .update();
        return new Removed(messages, tokens);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Removed(int messages, int tokens) {}
}
