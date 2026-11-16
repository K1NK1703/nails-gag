package gag.nails.notifications.internal.dispatch;

import gag.nails.booking.BookingQueries;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientQueries;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.id.UuidV7;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.internal.text.MessageTexts;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.MessageKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Отправка (спека §3.5). Прогон — два шага, каждый пачками по {@value #BATCH}, пока пачки полные.
 *
 * <p><b>Сообщения → доставки</b> ({@code dispatchDue}), одна короткая транзакция на пачку: созревшие сообщения
 * ({@code PENDING}, {@code due_at <= now}) берутся {@code FOR UPDATE SKIP LOCKED} по {@code due_at}, затем по моменту
 * события ({@code occurred_at}) и id; неактуальные (§5.2, {@link Relevance}) отменяются; для остальных определяются
 * доступные сейчас каналы ({@link ChannelResolver}) — по доставке на канал, и сообщение становится
 * {@code DISPATCHED} (без каналов — без доставок). Доставки создаются в той же транзакции, что и {@code DISPATCHED}:
 * у {@code PENDING}-сообщения доставок не бывает, поэтому удаление неотправленных напоминаний не задевает доставки.
 * Транзакция блокирует только свои строки сообщений, и то без ожидания ({@code SKIP LOCKED}): ни строки настроек, ни
 * advisory-блокировок, ни внешних вызовов — записи, карточки и получатели только читаются. Поэтому в порядке
 * блокировок очереди ({@code ReminderPlanner}) отправки нет: она никого не ждёт, а её ждут не дольше пачки.
 *
 * <p><b>Доставки → каналы</b> ({@code sendDue}). Созревшие доставки ({@code PENDING}, {@code next_attempt_at <=
 * now}) берутся {@code FOR UPDATE SKIP LOCKED}; истёкшие (срок жизни сообщения прошёл) и исчерпавшие попытки сразу
 * неудачны, доставки клиенткам в тихие часы откладываются до их конца, остальные берутся в аренду: попытка
 * засчитывается, {@code next_attempt_at} сдвигается на {@link RetryPolicy#LEASE} — и это фиксируется до отправки.
 * Параллельный прогон взятую доставку не видит; после падения посреди отправки она созреет снова (доставка «хотя бы
 * раз»). Затем вне транзакции, по одной: актуальность проверяется заново, адрес определяется сейчас — почта текущей
 * карточки, чат текущей привязки (после обезличивания повтор никуда не уходит), текст собирается из текущих записи и
 * карточки ({@link MessageTexts}), канал отправляет. Результат пишется новой короткой транзакцией — только если аренда
 * всё ещё своя (то же {@code next_attempt_at}).
 *
 * <p>Повторы — {@link RetryPolicy}: временный сбой откладывает доставку по расписанию, но не раньше срока, названного
 * каналом; доставке клиентке — ещё и из тихих часов; не успевающая до срока жизни сообщения — неудачна
 * ({@code expired}). Постоянный отказ канала — неудачна сразу; заблокировавший бота чат отвязывается. Каналы одного
 * сообщения независимы: сбой одного не трогает другой.
 *
 * <p>В лог — только id, вид, канал и код ошибки; сбой канала — WARN не чаще раза в минуту на канал.
 */
@Component
public class Dispatcher {

    /** Сколько сообщений или доставок за одну транзакцию. */
    static final int BATCH = 100;

    /** Системное чтение записей: отправке нужна любая запись, без актора запроса. */
    private static final Actor BOOKINGS_READER = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));

    private static final String STAFF_PREFIX = "STAFF_";

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final BookingQueries bookings;
    private final ClientQueries clients;
    private final ChannelResolver resolver;
    private final AccountChannels accountChannels;
    private final MessageTexts texts;
    private final QuietHours quietHours;
    private final Clock clock;
    private final ChannelWarnings warnings;

    Dispatcher(
            JdbcClient jdbc,
            TransactionTemplate transaction,
            BookingQueries bookings,
            ClientQueries clients,
            ChannelResolver resolver,
            AccountChannels accountChannels,
            MessageTexts texts,
            QuietHours quietHours,
            Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.bookings = bookings;
        this.clients = clients;
        this.resolver = resolver;
        this.accountChannels = accountChannels;
        this.texts = texts;
        this.quietHours = quietHours;
        this.clock = clock;
        this.warnings = new ChannelWarnings(clock);
    }

    /** Прогон: все созревшие сообщения — в доставки, затем все созревшие доставки — в каналы. Вне транзакции. */
    public void run() {
        requireNoTransaction();
        while (dispatchDue() == BATCH) {
            // Полная пачка — возможно, созревших больше.
        }
        while (sendDue() == BATCH) {
            // Так же.
        }
    }

    /**
     * Одна пачка созревших сообщений своей короткой транзакцией: неактуальные — {@code CANCELLED}, остальные —
     * доставки по доступным каналам и {@code DISPATCHED}.
     *
     * @return сколько сообщений взято
     */
    private int dispatchDue() {
        return Objects.requireNonNull(transaction.execute(status -> dispatchBatch()));
    }

    /**
     * Одна пачка созревших доставок: аренда в своей транзакции, отправка вне её, результат каждой — новой транзакцией.
     *
     * @return сколько доставок взято (в том числе неудачных сразу и отложенных из-за тихих часов)
     */
    private int sendDue() {
        var claimed = Objects.requireNonNull(transaction.execute(status -> claimDeliveries()));
        var leased = claimed.leased();
        if (!leased.isEmpty()) {
            var staff = resolver.staff();
            for (var delivery : leased) {
                send(delivery, staff);
            }
        }
        return claimed.taken();
    }

    private int dispatchBatch() {
        var now = now();
        var messages = jdbc.sql("""
                        select id, account_id, kind, booking_id, expires_at, occurred_at
                        from notifications.message
                        where status = 'PENDING' and due_at <= :now
                        order by due_at, occurred_at, id
                        limit :limit
                        for update skip locked
                        """)
                .param("now", timestamp(now))
                .param("limit", BATCH)
                .query(Dispatcher::dueMessage)
                .list();
        if (messages.isEmpty()) {
            return 0;
        }
        var staff = resolver.staff();
        var bookingsById = new HashMap<UUID, Optional<BookingView>>();
        var cancelled = new ArrayList<UUID>();
        var targets = new ArrayList<Targeted>();
        for (var message : messages) {
            var booking = bookingsById
                    .computeIfAbsent(message.bookingId(), id -> bookings.find(id, BOOKINGS_READER))
                    .orElse(null);
            if (!Relevance.relevant(message.kind(), booking, message.expiresAt(), message.occurredAt())) {
                cancelled.add(message.id());
            } else {
                var target =
                        resolver.target(message.kind(), message.accountId(), Objects.requireNonNull(booking), staff);
                targets.add(new Targeted(message, target.orElse(null)));
            }
        }
        var accountIds = targets.stream()
                .map(Targeted::target)
                .filter(Objects::nonNull)
                .map(ChannelResolver.Target::accountId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var switches = accountChannels.switches(accountIds);
        var linked = accountChannels.linkedToTelegram(accountIds);
        var deliveries = 0;
        for (var targeted : targets) {
            var target = targeted.target();
            var channels = target == null ? Set.<Channel>of() : resolver.available(target, switches, linked);
            var nextAttemptAt = isStaff(targeted.message().kind()) ? now : quietHours.shift(now);
            for (var channel : channels) {
                insertDelivery(targeted.message().id(), channel, nextAttemptAt);
                deliveries++;
            }
        }
        markDispatched(targets.stream().map(targeted -> targeted.message().id()).toList(), now);
        markCancelled(cancelled);
        log.info(
                "notifications dispatched {} messages with {} deliveries, cancelled {} outdated",
                targets.size(),
                deliveries,
                cancelled.size());
        return messages.size();
    }

    private void insertDelivery(UUID messageId, Channel channel, Instant nextAttemptAt) {
        jdbc.sql("""
                        insert into notifications.delivery (id, message_id, channel, status, attempts, next_attempt_at)
                        values (:id, :messageId, :channel, 'PENDING', 0, :nextAttemptAt)
                        """)
                .param("id", UuidV7.next())
                .param("messageId", messageId)
                .param("channel", channel.name())
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .update();
    }

    /** Сообщения взяты этой транзакцией ({@code FOR UPDATE}), поэтому всё ещё {@code PENDING}. */
    private void markDispatched(List<UUID> messageIds, Instant now) {
        if (!messageIds.isEmpty()) {
            jdbc.sql("""
                            update notifications.message set status = 'DISPATCHED', dispatched_at = :now
                            where id in (:ids)
                            """).param("now", timestamp(now)).param("ids", messageIds).update();
        }
    }

    private void markCancelled(List<UUID> messageIds) {
        if (!messageIds.isEmpty()) {
            jdbc.sql("update notifications.message set status = 'CANCELLED' where id in (:ids)")
                    .param("ids", messageIds)
                    .update();
        }
    }

    private Claimed claimDeliveries() {
        var now = now();
        var due = jdbc.sql("""
                        select d.id, d.message_id, d.channel, d.attempts, m.kind, m.booking_id, m.account_id,
                               m.expires_at, m.occurred_at
                        from notifications.delivery d
                        join notifications.message m on m.id = d.message_id
                        where d.status = 'PENDING' and d.next_attempt_at <= :now
                        order by d.next_attempt_at, m.occurred_at, d.id
                        limit :limit
                        for update of d skip locked
                        """)
                .param("now", timestamp(now))
                .param("limit", BATCH)
                .query(Dispatcher::dueDelivery)
                .list();
        var leased = new ArrayList<Leased>();
        for (var delivery : due) {
            if (!delivery.expiresAt().isAfter(now)) {
                fail(delivery.id(), ErrorCodes.EXPIRED);
                log.info("notification delivery {} of message {} expired", delivery.id(), delivery.messageId());
            } else if (!RetryPolicy.canRetry(delivery.attempts())) {
                fail(delivery.id(), ErrorCodes.TRANSIENT_EXHAUSTED);
                log.info(
                        "notification delivery {} of message {} has no attempts left",
                        delivery.id(),
                        delivery.messageId());
            } else if (!isStaff(delivery.kind()) && quietHours.shift(now).isAfter(now)) {
                postpone(delivery.id(), quietHours.shift(now));
            } else {
                var lease = now.plus(RetryPolicy.LEASE);
                jdbc.sql("""
                                update notifications.delivery
                                set attempts = attempts + 1, next_attempt_at = :lease
                                where id = :id
                                """)
                        .param("lease", timestamp(lease))
                        .param("id", delivery.id())
                        .update();
                leased.add(new Leased(delivery, delivery.attempts() + 1, lease));
            }
        }
        return new Claimed(due.size(), leased);
    }

    /** Доставка взята этой транзакцией ({@code FOR UPDATE}), поэтому всё ещё {@code PENDING}. */
    private void fail(UUID deliveryId, String errorCode) {
        jdbc.sql("update notifications.delivery set status = 'FAILED', error_code = :errorCode where id = :id")
                .param("errorCode", errorCode)
                .param("id", deliveryId)
                .update();
    }

    private void postpone(UUID deliveryId, Instant nextAttemptAt) {
        jdbc.sql("update notifications.delivery set next_attempt_at = :nextAttemptAt where id = :id")
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .param("id", deliveryId)
                .update();
    }

    /**
     * Отправка одной арендованной доставки вне транзакции. Ошибка самой отправки (не канала) оставляет доставку в
     * аренде: по её истечении будет следующая попытка, а попытки конечны.
     */
    private void send(Leased leased, Map<UUID, Set<String>> staff) {
        var delivery = leased.delivery();
        try {
            var booking = bookings.find(delivery.bookingId(), BOOKINGS_READER).orElse(null);
            if (!Relevance.relevant(delivery.kind(), booking, delivery.expiresAt(), delivery.occurredAt())) {
                record(leased, new Outcome.Failed(ErrorCodes.OUTDATED, null));
                return;
            }
            var bookingClient = clients.byId(booking.clientId()).orElse(null);
            var target = resolver.target(delivery.kind(), delivery.accountId(), booking, staff);
            var recipient = target.flatMap(found -> resolver.resolve(delivery.channel(), found));
            var channel = resolver.channel(delivery.channel());
            if (bookingClient == null || recipient.isEmpty() || channel.isEmpty()) {
                record(leased, new Outcome.Failed(ErrorCodes.UNAVAILABLE, null));
                return;
            }
            var text = RenderedText.of(texts.render(delivery.kind(), booking, bookingClient));
            var result = sendVia(channel.get(), recipient.get(), text, delivery);
            record(leased, outcome(result, leased, target.get().accountId(), recipient.get()));
        } catch (RuntimeException e) {
            log.error(
                    "notification delivery {} of message {} ({} via {}) could not be sent, it stays leased until {}",
                    delivery.id(),
                    delivery.messageId(),
                    delivery.kind(),
                    delivery.channel(),
                    leased.lease(),
                    e);
        }
    }

    /**
     * Исключение канала — временный сбой. В лог — только класс исключения: текст исключений почты и HTTP-клиентов
     * может содержать адрес.
     */
    private SendResult sendVia(
            NotificationChannel channel, ResolvedRecipient recipient, RenderedText text, DueDelivery delivery) {
        try {
            return channel.send(recipient, text);
        } catch (RuntimeException e) {
            log.debug(
                    "notification channel {} threw {} for delivery {}",
                    delivery.channel(),
                    e.getClass().getName(),
                    delivery.id());
            return new SendResult.Retry(null, ErrorCodes.CHANNEL_ERROR);
        }
    }

    /** Что записать по ответу канала; сбой канала — в лог с ограничением частоты. */
    private Outcome outcome(SendResult result, Leased leased, @Nullable UUID accountId, ResolvedRecipient recipient) {
        var delivery = leased.delivery();
        return switch (result) {
            case SendResult.Sent sent -> new Outcome.Sent();
            case SendResult.Fail fail -> {
                warnings.failed(
                        delivery.channel(),
                        delivery.id(),
                        delivery.messageId(),
                        delivery.kind().name(),
                        fail.errorCode());
                var chatId = recipient.chatId();
                var unlink = fail.unlinkTelegram() && accountId != null && chatId != null
                        ? new Unlink(accountId, chatId)
                        : null;
                yield new Outcome.Failed(fail.errorCode(), unlink);
            }
            case SendResult.Retry retry -> {
                warnings.failed(
                        delivery.channel(),
                        delivery.id(),
                        delivery.messageId(),
                        delivery.kind().name(),
                        retry.errorCode());
                yield retryOutcome(retry, leased);
            }
        };
    }

    private Outcome retryOutcome(SendResult.Retry retry, Leased leased) {
        if (!RetryPolicy.canRetry(leased.attempts())) {
            return new Outcome.Failed(ErrorCodes.TRANSIENT_EXHAUSTED, null);
        }
        var now = now();
        var next = now.plus(RetryPolicy.pauseAfter(leased.attempts()));
        var notBefore = retry.notBefore();
        if (notBefore != null && now.plus(notBefore).isAfter(next)) {
            next = now.plus(notBefore);
        }
        if (!isStaff(leased.delivery().kind())) {
            next = quietHours.shift(next);
        }
        if (!next.isBefore(leased.delivery().expiresAt())) {
            return new Outcome.Failed(ErrorCodes.EXPIRED, null);
        }
        return new Outcome.Retrying(next, retry.errorCode());
    }

    /**
     * Пишет исход новой транзакцией — только пока аренда своя: доставка всё ещё {@code PENDING} с тем же
     * {@code next_attempt_at}. Иначе (отправка шла дольше аренды, и доставку взял другой прогон) исход пишет тот.
     */
    private void record(Leased leased, Outcome outcome) {
        var delivery = leased.delivery();
        transaction.executeWithoutResult(status -> {
            var statement = switch (outcome) {
                case Outcome.Sent sent ->
                    leasedUpdate("status = 'SENT', sent_at = :now", leased).param("now", timestamp(now()));
                case Outcome.Failed failed ->
                    leasedUpdate("status = 'FAILED', error_code = :errorCode", leased)
                            .param("errorCode", failed.errorCode());
                case Outcome.Retrying retrying ->
                    leasedUpdate("next_attempt_at = :next, error_code = :errorCode", leased)
                            .param("next", timestamp(retrying.next()))
                            .param("errorCode", retrying.errorCode());
            };
            if (statement.update() == 0) {
                log.info("notification delivery {} lease was lost, its result is not recorded", delivery.id());
                return;
            }
            if (outcome instanceof Outcome.Failed failed) {
                log.info(
                        "notification delivery {} of message {} ({} via {}) failed: {}",
                        delivery.id(),
                        delivery.messageId(),
                        delivery.kind(),
                        delivery.channel(),
                        failed.errorCode());
                var unlink = failed.unlink();
                if (unlink != null && accountChannels.unlinkTelegram(unlink.accountId(), unlink.chatId())) {
                    log.info("telegram binding removed: the bot is blocked (delivery {})", delivery.id());
                }
            }
        });
    }

    /** Изменение доставки, пока аренда своя: {@code PENDING} с тем же {@code next_attempt_at}. */
    private JdbcClient.StatementSpec leasedUpdate(String assignments, Leased leased) {
        return jdbc.sql("update notifications.delivery set " + assignments
                        + " where id = :id and status = 'PENDING' and next_attempt_at = :lease")
                .param("id", leased.delivery().id())
                .param("lease", timestamp(leased.lease()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isStaff(MessageKind kind) {
        return kind.name().startsWith(STAFF_PREFIX);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("notification dispatch must run outside a transaction");
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant nullableInstant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static DueMessage dueMessage(ResultSet row, int rowNumber) throws SQLException {
        return new DueMessage(
                row.getObject("id", UUID.class),
                row.getObject("account_id", UUID.class),
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("booking_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                nullableInstant(row.getTimestamp("occurred_at")));
    }

    private static DueDelivery dueDelivery(ResultSet row, int rowNumber) throws SQLException {
        return new DueDelivery(
                row.getObject("id", UUID.class),
                row.getObject("message_id", UUID.class),
                Channel.valueOf(row.getString("channel")),
                row.getInt("attempts"),
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("booking_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                nullableInstant(row.getTimestamp("occurred_at")));
    }

    /** Созревшее сообщение; получатель-карточка не нужен — клиентке пишем по текущей карточке записи. */
    private record DueMessage(
            UUID id,
            @Nullable UUID accountId,
            MessageKind kind,
            UUID bookingId,
            Instant expiresAt,
            @Nullable Instant occurredAt) {}

    private record Targeted(DueMessage message, ChannelResolver.@Nullable Target target) {}

    /** Созревшая доставка с полями своего сообщения. */
    private record DueDelivery(
            UUID id,
            UUID messageId,
            Channel channel,
            int attempts,
            MessageKind kind,
            UUID bookingId,
            @Nullable UUID accountId,
            Instant expiresAt,
            @Nullable Instant occurredAt) {}

    /**
     * Доставка в аренде.
     *
     * @param attempts попыток вместе с этой
     * @param lease до какого момента аренда: значение {@code next_attempt_at}, по которому пишется исход
     */
    private record Leased(DueDelivery delivery, int attempts, Instant lease) {}

    /**
     * @param taken сколько доставок взято
     * @param leased взятые в аренду для отправки
     */
    private record Claimed(int taken, List<Leased> leased) {}

    /** Привязка бота, которую снять: аккаунт получателя и чат, куда не удалось отправить. */
    private record Unlink(UUID accountId, long chatId) {}

    /** Исход попытки для записи. */
    private sealed interface Outcome {

        record Sent() implements Outcome {}

        /** @param unlink снять эту привязку бота (чат заблокировал бота); пусто — привязки не трогать */
        record Failed(String errorCode, @Nullable Unlink unlink) implements Outcome {}

        record Retrying(Instant next, String errorCode) implements Outcome {}
    }
}
