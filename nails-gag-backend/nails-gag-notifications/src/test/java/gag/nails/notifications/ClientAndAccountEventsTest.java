package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.common.actor.Actor;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.Accounts;
import gag.nails.identity.event.AccountDeleted;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import gag.nails.testsupport.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * События карточек и аккаунтов (спека §3.10) обрабатываются синхронно, в транзакции источника: обезличивание,
 * объединение и удаление аккаунта — настоящими операциями модулей. Отправленные сообщения — история, их эти события
 * не трогают. Каждый тест в транзакции с откатом, кроме проверки, что вне транзакции эти события отвергаются.
 */
@NotificationsModuleTest
@Transactional
class ClientAndAccountEventsTest {

    private static final Instant DUE = Instant.parse("2026-10-02T06:00:00Z");

    @Autowired
    ClientService clients;

    @Autowired
    Accounts accounts;

    @Autowired
    AccessAdministration access;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void clientErasedCancelsPending() {
        var anna = card("Анна");
        var other = card("Мария");
        var pending = message(MessageKind.CONFIRMED, anna, null, MessageStatus.PENDING);
        var dispatched = message(MessageKind.BOOKING_RECEIVED, anna, null, MessageStatus.DISPATCHED);
        var othersPending = message(MessageKind.CONFIRMED, other, null, MessageStatus.PENDING);

        clients.erase(anna);

        assertThat(status(pending)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(status(dispatched)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(status(othersPending)).isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void clientMergedMovesPending() {
        var source = card("Анна");
        var target = card("Анна");
        var pending = message(MessageKind.CONFIRMED, source, null, MessageStatus.PENDING);
        var dispatched = message(MessageKind.BOOKING_RECEIVED, source, null, MessageStatus.DISPATCHED);
        var targetsPending = message(MessageKind.RESCHEDULED, target, null, MessageStatus.PENDING);

        var result = clients.merge(source, target, Actor.master());

        assertThat(result.survivorId()).isEqualTo(target);
        assertThat(recipient(pending)).isEqualTo(target);
        assertThat(status(pending)).isEqualTo(MessageStatus.PENDING);
        assertThat(recipient(dispatched)).isEqualTo(source);
        assertThat(recipient(targetsPending)).isEqualTo(target);
    }

    @Test
    void accountDeletedRemovesBindingAndCancelsStaffMessages() {
        var master = masterAccount();
        var otherMaster = masterAccount();
        var actor = Actor.of(master, null, Actor.masterAccess());
        preferences.update(new UpdateMyChannels(true, true), actor);
        linkTelegram(master);
        linkTelegram(otherMaster);
        jdbc.sql("insert into notifications.link_token (id, account_id, token_hash, expires_at) values (?, ?, ?, ?)")
                .params(
                        UUID.randomUUID(),
                        master,
                        randomBytes(32),
                        Timestamp.from(clock.instant().plus(Duration.ofMinutes(10))))
                .update();
        var pending = message(MessageKind.STAFF_NEW_BOOKING, null, master, MessageStatus.PENDING);
        var dispatched = message(MessageKind.STAFF_CLIENT_CANCELLED, null, master, MessageStatus.DISPATCHED);
        var othersPending = message(MessageKind.STAFF_NEW_BOOKING, null, otherMaster, MessageStatus.PENDING);

        accounts.delete(actor);

        assertThat(count("telegram_chat", master)).isZero();
        assertThat(count("preference", master)).isZero();
        assertThat(count("link_token", master)).isZero();
        assertThat(count("telegram_chat", otherMaster)).isOne();
        assertThat(status(pending)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(status(dispatched)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(status(othersPending)).isEqualTo(MessageStatus.PENDING);
    }

    /**
     * Объединение карточек, у обеих из которых аккаунт: модуль входа удаляет аккаунт удаляемой карточки
     * ({@code AccountDeleted}) — его каналы забываются, мастерские сообщения отменяются; сообщения клиентке переходят
     * оставшейся карточке, каналы оставшегося аккаунта не трогаются.
     */
    @Test
    void mergeOfTwoAccountsForgetsRemovedAccount() {
        var removedAccount = account();
        var survivorAccount = account();
        var source = clients.register(new RegisterClient(removedAccount, "Анна", null, null))
                .id();
        var target = clients.register(new RegisterClient(survivorAccount, "Анна", null, null))
                .id();
        preferences.update(new UpdateMyChannels(true, false), Actor.of(removedAccount, source, Map.of()));
        linkTelegram(removedAccount);
        linkTelegram(survivorAccount);
        var staffPending = message(MessageKind.STAFF_NEW_BOOKING, null, removedAccount, MessageStatus.PENDING);
        var clientPending = message(MessageKind.CONFIRMED, source, null, MessageStatus.PENDING);

        var result = clients.merge(source, target, Actor.master());

        assertThat(result.survivorId()).isEqualTo(target);
        assertThat(count("telegram_chat", removedAccount)).isZero();
        assertThat(count("preference", removedAccount)).isZero();
        assertThat(count("telegram_chat", survivorAccount)).isOne();
        assertThat(status(staffPending)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(recipient(clientPending)).isEqualTo(target);
        assertThat(status(clientPending)).isEqualTo(MessageStatus.PENDING);
    }

    /**
     * Слушатели работают только в транзакции источника: без неё событие отвергается, а не обрабатывается в своей
     * транзакции. Проверка — на {@link AccountDeleted}: у событий карточек есть и другие синхронные слушатели.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eventsOutsideSourceTransactionAreRejected() {
        var event = new AccountDeleted(UUID.randomUUID(), clock.instant());

        assertThatThrownBy(() -> publisher.publishEvent(event)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), Actor.master())
                .id();
    }

    /** Аккаунт мастера без карточки клиента: строка аккаунта — напрямую, роль — через права. */
    private UUID masterAccount() {
        var accountId = account();
        var masterRole = access.roles(Actor.developer()).stream()
                .filter(role -> role.code().equals("MASTER"))
                .findFirst()
                .orElseThrow();
        access.assignRole(accountId, masterRole.id(), Actor.developer());
        return accountId;
    }

    /** Аккаунт входа без способов входа: строка — напрямую (вход в тестах модуля не нужен). */
    private UUID account() {
        var accountId = UUID.randomUUID();
        jdbc.sql("insert into identity.account (id, created_at, last_login_at) values (?, ?, ?)")
                .params(accountId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()))
                .update();
        return accountId;
    }

    private void linkTelegram(UUID accountId) {
        jdbc.sql("insert into notifications.telegram_chat (id, account_id, chat_id, linked_at) values (?, ?, ?, ?)")
                .params(
                        UUID.randomUUID(),
                        accountId,
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                        Timestamp.from(clock.instant()))
                .update();
    }

    private UUID message(MessageKind kind, @Nullable UUID clientId, @Nullable UUID accountId, MessageStatus status) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.message (id, client_id, account_id, kind, booking_id, due_at,
                            expires_at, status, dedup_key, created_at, dispatched_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        clientId,
                        accountId,
                        kind.name(),
                        UUID.randomUUID(),
                        Timestamp.from(DUE),
                        Timestamp.from(DUE.plus(Duration.ofDays(1))),
                        status.name(),
                        "test:" + id,
                        Timestamp.from(clock.instant()),
                        status == MessageStatus.DISPATCHED ? Timestamp.from(clock.instant()) : null)
                .update();
        return id;
    }

    private MessageStatus status(UUID messageId) {
        return MessageStatus.valueOf(jdbc.sql("select status from notifications.message where id = ?")
                .param(messageId)
                .query(String.class)
                .single());
    }

    private UUID recipient(UUID messageId) {
        return jdbc.sql("select client_id from notifications.message where id = ?")
                .param(messageId)
                .query(UUID.class)
                .single();
    }

    private long count(String table, UUID accountId) {
        return jdbc.sql("select count(*) from notifications." + table + " where account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }

    private static byte[] randomBytes(int length) {
        var bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
