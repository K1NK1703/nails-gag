package gag.nails.notifications.internal.dispatch;

import gag.nails.booking.BookingQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.id.UuidV7;
import gag.nails.notifications.internal.Failures;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.internal.text.MessageTexts;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.MessageKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Отправка (спека §3.5). Прогон — два шага; сбой первого не мешает второму, оба сбоя прогон сообщает после.
 *
 * <p><b>Сообщения → доставки</b> ({@code dispatchDue}) — по одному сообщению в своей короткой транзакции: созревшее
 * сообщение ({@code PENDING}, {@code due_at <= now}) берётся {@code FOR UPDATE SKIP LOCKED} по {@code due_at}, затем
 * по моменту события ({@code occurred_at}) и id; истёкшее отменяется без чтения записи; неактуальное (§5.2,
 * {@link Relevance}) отменяется; для остальных определяются доступные сейчас каналы ({@link ChannelResolver}) — по
 * доставке на канал, и сообщение становится {@code DISPATCHED} (без каналов — без доставок). Доставки создаются в той
 * же транзакции, что и {@code DISPATCHED}: у {@code PENDING}-сообщения доставок не бывает, поэтому удаление
 * неотправленных напоминаний не задевает доставки. Сообщение, на котором транзакция упала, остаётся {@code PENDING} и в
 * этом прогоне больше не берётся — остальные идут дальше; когда истечёт, оно отменится, не читая записи. Транзакция
 * блокирует только строку своего сообщения, и то без ожидания ({@code SKIP LOCKED}): ни строки настроек, ни
 * advisory-блокировок, ни внешних вызовов — записи, карточки и получатели только читаются. Поэтому в порядке
 * блокировок очереди ({@code ReminderPlanner}) отправки нет: она никого не ждёт, а её ждут не дольше одного сообщения.
 *
 * <p><b>Доставки → каналы</b> ({@code sendDue}). Созревшие доставки ({@code PENDING}, {@code next_attempt_at <= now})
 * читаются пачкой без блокировок, и каждая берётся в аренду прямо перед своей отправкой — короткой транзакцией с
 * {@code FOR UPDATE SKIP LOCKED} и повторной проверкой срока: истёкшая (срок жизни сообщения прошёл) и исчерпавшая
 * попытки сразу неудачны, доставка клиентке в тихие часы откладывается до их конца, остальная берётся в аренду: попытка
 * засчитывается, {@code next_attempt_at} сдвигается на {@link RetryPolicy#LEASE}, и это фиксируется до отправки. Аренда
 * отсчитывается от начала своей отправки, а не от начала пачки: медленный канал не съедает аренду следующих доставок.
 * Параллельный прогон взятую доставку не видит; после падения посреди отправки она созреет снова (доставка «хотя бы
 * раз»). Затем вне транзакции: актуальность проверяется заново, адрес определяется сейчас — почта текущей карточки, чат
 * текущей привязки (после обезличивания повтор никуда не уходит), текст собирается из текущих записи и карточки
 * ({@link MessageTexts}), канал отправляет. Результат пишется новой короткой транзакцией — только если аренда всё ещё
 * своя (то же {@code next_attempt_at}).
 *
 * <p>Повторы — {@link RetryPolicy}: временный сбой откладывает доставку по расписанию, но не раньше срока, названного
 * каналом; доставке клиентке — ещё и из тихих часов; не успевающая до срока жизни сообщения — неудачна
 * ({@code expired}). Постоянный отказ канала — неудачна сразу; заблокировавший бота чат отвязывается. Каналы одного
 * сообщения независимы: сбой одного не трогает другой.
 *
 * <p>В лог — только id, вид, канал и код ошибки; сбой канала — WARN не чаще раза в минуту на канал; ошибки базы — класс
 * и SQLState без текста ({@link Failures}).
 */
@Component
public class Dispatcher {

    /** Сколько созревших доставок читается за раз. */
    static final int BATCH = 100;

    /** Системное чтение записей: отправке нужна любая запись, без актора запроса. */
    private static final Actor BOOKINGS_READER = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));

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

    /**
     * Прогон: все созревшие сообщения — в доставки, затем все созревшие доставки — в каналы. Отправка идёт и тогда,
     * когда первый шаг упал; упавший шаг (оба — первый с подавленным вторым) прогон бросает в конце. Вне транзакции.
     */
    public void run() {
        requireNoTransaction();
        RuntimeException failure = null;
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            sendDue();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        warnings.flush();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Все созревшие сообщения, по одному в своей транзакции. Сообщение, на котором транзакция упала, исключается до
     * конца прогона, поэтому цикл конечен; упал сам выбор сообщения (база недоступна) — шаг прерывается.
     */
    private void dispatchDue() {
        var failed = new HashSet<UUID>();
        var failures = new RunFailures("messages");
        var run = new DispatchRun(new Cards(clients));
        while (true) {
            var current = new AtomicReference<@Nullable DueMessage>();
            try {
                if (!Objects.requireNonNull(transaction.execute(status -> dispatchNext(failed, current, run)))) {
                    break;
                }
            } catch (RuntimeException e) {
                var message = current.get();
                if (message == null) {
                    throw e;
                }
                failed.add(message.id());
                failures.failed(
                        "notification message " + message.id() + " (" + message.kind() + ")",
                        "not dispatched, it stays pending",
                        e);
            }
        }
        if (run.dispatched > 0 || run.cancelled > 0 || !failed.isEmpty()) {
            log.info(
                    "notifications dispatched {} messages with {} deliveries, cancelled {}, failed {}",
                    run.dispatched,
                    run.deliveries,
                    run.cancelled,
                    failed.size());
        }
        failures.summarize();
    }

    /** Одно созревшее сообщение; {@code false} — созревших (кроме упавших в этом прогоне) нет. */
    private boolean dispatchNext(Set<UUID> failed, AtomicReference<@Nullable DueMessage> current, DispatchRun run) {
        var now = now();
        var message = jdbc.sql("""
                        select id, account_id, kind, booking_id, expires_at, occurred_at
                        from notifications.message
                        where status = 'PENDING' and due_at <= :now
                        """ + (failed.isEmpty() ? "" : " and id not in (:failed)") + """
                         order by due_at, occurred_at, id
                         limit 1
                         for update skip locked
                        """)
                .params(
                        failed.isEmpty()
                                ? Map.of("now", timestamp(now))
                                : Map.of("now", timestamp(now), "failed", failed))
                .query(Dispatcher::dueMessage)
                .optional()
                .orElse(null);
        if (message == null) {
            return false;
        }
        current.set(message);
        if (!message.expiresAt().isAfter(now)) {
            markCancelled(message.id());
            run.cancelled++;
            return true;
        }
        var booking = bookings.find(message.bookingId(), BOOKINGS_READER).orElse(null);
        if (!Relevance.relevant(message.kind(), booking, message.expiresAt(), message.occurredAt())) {
            markCancelled(message.id());
            run.cancelled++;
            return true;
        }
        var channels = resolver.target(
                        message.kind(), message.accountId(), Objects.requireNonNull(booking), run.staff(), run.cards)
                .map(resolver::available)
                .orElse(Set.of());
        var nextAttemptAt = message.kind().isStaff() ? now : quietHours.shift(now);
        insertDeliveries(message.id(), channels, nextAttemptAt);
        markDispatched(message.id(), now);
        run.dispatched++;
        run.deliveries += channels.size();
        return true;
    }

    /** Все доставки сообщения — одним оператором. */
    private void insertDeliveries(UUID messageId, Set<Channel> channels, Instant nextAttemptAt) {
        if (channels.isEmpty()) {
            return;
        }
        var rows = new ArrayList<String>();
        var statement = new ArrayList<Channel>(channels);
        for (var i = 0; i < statement.size(); i++) {
            rows.add("(:id" + i + ", :messageId, :channel" + i + ", 'PENDING', 0, :nextAttemptAt)");
        }
        var insert = jdbc.sql("insert into notifications.delivery"
                        + " (id, message_id, channel, status, attempts, next_attempt_at) values "
                        + String.join(", ", rows))
                .param("messageId", messageId)
                .param("nextAttemptAt", timestamp(nextAttemptAt));
        for (var i = 0; i < statement.size(); i++) {
            insert = insert.param("id" + i, UuidV7.next())
                    .param("channel" + i, statement.get(i).name());
        }
        insert.update();
    }

    /** Сообщение взято этой транзакцией ({@code FOR UPDATE}), поэтому всё ещё {@code PENDING}. */
    private void markDispatched(UUID messageId, Instant now) {
        jdbc.sql("update notifications.message set status = 'DISPATCHED', dispatched_at = :now where id = :id")
                .param("now", timestamp(now))
                .param("id", messageId)
                .update();
    }

    private void markCancelled(UUID messageId) {
        jdbc.sql("update notifications.message set status = 'CANCELLED' where id = :id")
                .param("id", messageId)
                .update();
    }

    /**
     * Все созревшие доставки: пачка читается без блокировок, каждая доставка берётся в аренду и отправляется по одной.
     * Доставка, на взятии которой транзакция упала, исключается до конца прогона. Следующая пачка читается, только
     * если эта была полной и что-то сдвинула: иначе остались лишь доставки, которые прямо сейчас берёт другой прогон.
     */
    private void sendDue() {
        var failed = new HashSet<UUID>();
        var failures = new RunFailures("deliveries");
        Map<UUID, Set<String>> staff = null;
        while (true) {
            var candidates = dueDeliveries(failed);
            var progressed = 0;
            for (var deliveryId : candidates) {
                Claim claim;
                try {
                    claim = Objects.requireNonNull(transaction.execute(status -> claim(deliveryId)));
                } catch (RuntimeException e) {
                    failed.add(deliveryId);
                    failures.failed("notification delivery " + deliveryId, "claim failed", e);
                    continue;
                }
                if (claim instanceof Claim.Leased leased) {
                    if (staff == null) {
                        staff = resolver.staff();
                    }
                    send(leased, staff, failures);
                }
                if (!(claim instanceof Claim.Skipped)) {
                    progressed++;
                }
            }
            if (candidates.size() < BATCH || progressed == 0) {
                break;
            }
        }
        failures.summarize();
    }

    private List<UUID> dueDeliveries(Set<UUID> failed) {
        var now = now();
        return jdbc.sql("""
                        select d.id from notifications.delivery d
                        join notifications.message m on m.id = d.message_id
                        where d.status = 'PENDING' and d.next_attempt_at <= :now
                        """ + (failed.isEmpty() ? "" : " and d.id not in (:failed)") + """
                         order by d.next_attempt_at, m.occurred_at, d.id
                         limit :limit
                        """)
                .params(
                        failed.isEmpty()
                                ? Map.of("now", timestamp(now))
                                : Map.of("now", timestamp(now), "failed", failed))
                .param("limit", BATCH)
                .query(UUID.class)
                .list();
    }

    /**
     * Берёт доставку, если она всё ещё созревшая и её не держит другой прогон: истёкшая или исчерпавшая попытки —
     * неудачна, клиентке в тихие часы — отложена, иначе — в аренду.
     */
    private Claim claim(UUID deliveryId) {
        var now = now();
        var delivery = jdbc.sql("""
                        select d.id, d.message_id, d.channel, d.attempts, m.kind, m.booking_id, m.account_id,
                               m.expires_at, m.occurred_at
                        from notifications.delivery d
                        join notifications.message m on m.id = d.message_id
                        where d.id = :id and d.status = 'PENDING' and d.next_attempt_at <= :now
                        for update of d skip locked
                        """)
                .param("id", deliveryId)
                .param("now", timestamp(now))
                .query(Dispatcher::dueDelivery)
                .optional()
                .orElse(null);
        if (delivery == null) {
            return new Claim.Skipped();
        }
        if (!delivery.expiresAt().isAfter(now)) {
            fail(delivery.id(), ErrorCodes.EXPIRED);
            log.info("notification delivery {} of message {} expired", delivery.id(), delivery.messageId());
            return new Claim.Finished();
        }
        if (!RetryPolicy.canRetry(delivery.attempts())) {
            fail(delivery.id(), ErrorCodes.TRANSIENT_EXHAUSTED);
            log.info(
                    "notification delivery {} of message {} has no attempts left", delivery.id(), delivery.messageId());
            return new Claim.Finished();
        }
        var quietEnd = quietHours.shift(now);
        if (!delivery.kind().isStaff() && quietEnd.isAfter(now)) {
            jdbc.sql("update notifications.delivery set next_attempt_at = :next where id = :id")
                    .param("next", timestamp(quietEnd))
                    .param("id", delivery.id())
                    .update();
            return new Claim.Finished();
        }
        var lease = now.plus(RetryPolicy.LEASE);
        jdbc.sql("update notifications.delivery set attempts = attempts + 1, next_attempt_at = :lease where id = :id")
                .param("lease", timestamp(lease))
                .param("id", delivery.id())
                .update();
        return new Claim.Leased(delivery, delivery.attempts() + 1, lease);
    }

    /** Доставка взята этой транзакцией ({@code FOR UPDATE}), поэтому всё ещё {@code PENDING}. */
    private void fail(UUID deliveryId, String errorCode) {
        jdbc.sql("update notifications.delivery set status = 'FAILED', error_code = :errorCode where id = :id")
                .param("errorCode", errorCode)
                .param("id", deliveryId)
                .update();
    }

    /**
     * Отправка одной арендованной доставки вне транзакции. Ошибка подготовки (чтение, адрес, текст) или записи исхода
     * оставляет доставку в аренде: по её истечении будет следующая попытка, а попытки конечны.
     */
    private void send(Claim.Leased leased, Map<UUID, Set<String>> staff, RunFailures failures) {
        var delivery = leased.delivery();
        Prepared prepared;
        try {
            prepared = prepare(leased, staff);
        } catch (RuntimeException e) {
            failures.failed("notification delivery " + delivery.id(), "render/resolve failed", e);
            return;
        }
        var outcome = prepared.outcome();
        var sent = false;
        if (outcome == null) {
            var result = sendVia(
                    Objects.requireNonNull(prepared.channel()),
                    Objects.requireNonNull(prepared.recipient()),
                    Objects.requireNonNull(prepared.text()),
                    delivery);
            outcome = outcome(result, leased, prepared.accountId(), Objects.requireNonNull(prepared.recipient()));
            sent = true;
        }
        try {
            record(leased, outcome);
        } catch (RuntimeException e) {
            failures.failed(
                    "notification delivery " + delivery.id(),
                    sent ? "result not recorded after send" : "result not recorded",
                    e);
        }
    }

    /** Всё, что нужно для отправки, по текущим данным; или исход без отправки (неактуально, недоступно). */
    private Prepared prepare(Claim.Leased leased, Map<UUID, Set<String>> staff) {
        var delivery = leased.delivery();
        var booking = bookings.find(delivery.bookingId(), BOOKINGS_READER).orElse(null);
        if (booking == null
                || !Relevance.relevant(delivery.kind(), booking, delivery.expiresAt(), delivery.occurredAt())) {
            return Prepared.finished(new Outcome.Failed(ErrorCodes.OUTDATED, null));
        }
        var cards = new Cards(clients);
        var bookingClient = cards.byId(booking.clientId()).orElse(null);
        var target = resolver.target(delivery.kind(), delivery.accountId(), booking, staff, cards)
                .orElse(null);
        var recipient = target == null
                ? null
                : resolver.resolve(delivery.channel(), target).orElse(null);
        var channel = resolver.channel(delivery.channel()).orElse(null);
        if (bookingClient == null || target == null || recipient == null || channel == null) {
            return Prepared.finished(new Outcome.Failed(ErrorCodes.UNAVAILABLE, null));
        }
        var text = RenderedText.of(texts.render(delivery.kind(), booking, bookingClient));
        return new Prepared(null, channel, recipient, text, target.accountId());
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
    private Outcome outcome(
            SendResult result, Claim.Leased leased, @Nullable UUID accountId, ResolvedRecipient recipient) {
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

    private Outcome retryOutcome(SendResult.Retry retry, Claim.Leased leased) {
        if (!RetryPolicy.canRetry(leased.attempts())) {
            return new Outcome.Failed(ErrorCodes.TRANSIENT_EXHAUSTED, null);
        }
        var now = now();
        var next = now.plus(RetryPolicy.pauseAfter(leased.attempts()));
        var notBefore = retry.notBefore();
        if (notBefore != null && now.plus(notBefore).isAfter(next)) {
            next = now.plus(notBefore);
        }
        if (!leased.delivery().kind().isStaff()) {
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
    private void record(Claim.Leased leased, Outcome outcome) {
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
                    log.info(
                            "telegram binding removed: the chat is unreachable for the bot, {} (delivery {})",
                            failed.errorCode(),
                            delivery.id());
                }
            }
        });
    }

    /** Изменение доставки, пока аренда своя: {@code PENDING} с тем же {@code next_attempt_at}. */
    private JdbcClient.StatementSpec leasedUpdate(String assignments, Claim.Leased leased) {
        return jdbc.sql("update notifications.delivery set " + assignments
                        + " where id = :id and status = 'PENDING' and next_attempt_at = :lease")
                .param("id", leased.delivery().id())
                .param("lease", timestamp(leased.lease()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("notification dispatch must run outside a transaction");
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static DueMessage dueMessage(ResultSet row, int rowNumber) throws SQLException {
        return new DueMessage(
                row.getObject("id", UUID.class),
                row.getObject("account_id", UUID.class),
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("booking_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("occurred_at").toInstant());
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
                row.getTimestamp("occurred_at").toInstant());
    }

    /**
     * Сбои шага прогона, не связанные с каналом (спека §6): ошибки базы (где угодно в цепочке причин) — класс и
     * SQLState без текста, WARN; прочие (ошибки кода) — стек только у первой, остальные — строкой и итоговым числом.
     */
    private static final class RunFailures {

        /** Что за шаг: «messages» или «deliveries» — для итоговой строки. */
        private final String items;

        private int codeFailures;

        RunFailures(String items) {
            this.items = items;
        }

        /**
         * @param subject о ком: «notification message <id> (<вид>)», «notification delivery <id>»
         * @param what что не удалось
         */
        void failed(String subject, String what, RuntimeException failure) {
            if (Failures.isDatabase(failure)) {
                log.warn("{}: {}: {}", subject, what, Failures.describe(failure));
                return;
            }
            codeFailures++;
            if (codeFailures == 1) {
                log.error("{}: {}", subject, what, failure);
            } else {
                log.error("{}: {}: {}", subject, what, Failures.describe(failure));
            }
        }

        void summarize() {
            if (codeFailures > 1) {
                log.error(
                        "{} notification {} failed in this run, the stack trace is logged for the first",
                        codeFailures,
                        items);
            }
        }
    }

    /** Прогон постановки доставок: получатели-сотрудники — один раз, карточки — по разу. */
    private final class DispatchRun {

        private final Cards cards;
        private @Nullable Map<UUID, Set<String>> staff;
        private int dispatched;
        private int deliveries;
        private int cancelled;

        DispatchRun(Cards cards) {
            this.cards = cards;
        }

        Map<UUID, Set<String>> staff() {
            var loaded = staff;
            if (loaded == null) {
                loaded = resolver.staff();
                staff = loaded;
            }
            return loaded;
        }
    }

    /** Созревшее сообщение; получатель-карточка не нужен — клиентке пишем по текущей карточке записи. */
    private record DueMessage(
            UUID id,
            @Nullable UUID accountId,
            MessageKind kind,
            UUID bookingId,
            Instant expiresAt,
            Instant occurredAt) {}

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
            Instant occurredAt) {}

    /** Что стало с доставкой при взятии. */
    private sealed interface Claim {

        /** Уже не созревшая или её держит другой прогон. */
        record Skipped() implements Claim {}

        /** Неудачна сразу или отложена до конца тихих часов. */
        record Finished() implements Claim {}

        /**
         * В аренде.
         *
         * @param attempts попыток вместе с этой
         * @param lease до какого момента аренда: значение {@code next_attempt_at}, по которому пишется исход
         */
        record Leased(DueDelivery delivery, int attempts, Instant lease) implements Claim {}
    }

    /**
     * Подготовленная отправка: исход без отправки ({@code outcome}) или канал, адрес и текст.
     *
     * @param accountId аккаунт получателя — для отвязки заблокированного бота
     */
    private record Prepared(
            @Nullable Outcome outcome,
            @Nullable NotificationChannel channel,
            @Nullable ResolvedRecipient recipient,
            @Nullable RenderedText text,
            @Nullable UUID accountId) {

        static Prepared finished(Outcome outcome) {
            return new Prepared(outcome, null, null, null, null);
        }
    }

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
