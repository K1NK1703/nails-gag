package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientService;
import gag.nails.clients.command.RegisterClient;
import gag.nails.common.actor.Actor;
import gag.nails.identity.Accounts;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.command.UpdateMyChannels;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Данные уведомлений об аккаунте (ссылка привязки бота, настройка каналов) не переживают одновременное удаление
 * аккаунта: запись держит строку {@code identity.account} {@code FOR KEY SHARE} ({@code Accounts.lockExisting}),
 * удаление — {@code FOR UPDATE} (владельцем и при объединении карточек). Порядок задаётся ожиданием блокировки строки
 * аккаунта ({@link ConcurrencySupport#whileRowIsHeld}). Бот настроен (токен и имя), приём его сообщений в тестах
 * выключен — запросов к Telegram нет. Тест удаляет свои строки.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "nails-gag.booking.jobs.enabled=false",
            "nails-gag.notifications.telegram.bot-token=4242:concurrency-token",
            "nails-gag.notifications.telegram.bot-username=nails_gag_bot"
        })
@Import(PostgresTestcontainers.class)
class AccountDataConcurrencyTest {

    @Autowired
    Accounts accounts;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    ClientService clients;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final List<UUID> accountIds = new ArrayList<>();
    private final List<UUID> clientIds = new ArrayList<>();
    private ConcurrencySupport locks;

    @BeforeEach
    void locks() {
        locks = new ConcurrencySupport(transaction, jdbc, "%identity.account%");
    }

    @AfterEach
    void removeOwn() {
        if (!accountIds.isEmpty()) {
            for (var table : List.of("link_token", "telegram_chat", "preference")) {
                jdbc.sql("delete from notifications." + table + " where account_id in (:ids)")
                        .param("ids", accountIds)
                        .update();
            }
        }
        if (!clientIds.isEmpty()) {
            jdbc.sql("delete from clients.client where id in (:ids)")
                    .param("ids", clientIds)
                    .update();
        }
        if (!accountIds.isEmpty()) {
            jdbc.sql("delete from identity.account where id in (:ids)")
                    .param("ids", accountIds)
                    .update();
        }
    }

    @Test
    void linkWaitingForDeletionIsRefused() throws Exception {
        var anna = account();

        var link = locks.whileRowIsHeld(() -> accounts.delete(anna), () -> preferences.createTelegramLink(anna));

        assertThat(link.failure()).isInstanceOf(AccountNotFoundException.class);
        assertThat(rows("link_token", anna)).isZero();
    }

    @Test
    void deletionWaitingForLinkRemovesItsToken() throws Exception {
        var anna = account();

        var deleted = locks.whileRowIsHeld(() -> preferences.createTelegramLink(anna), () -> accounts.delete(anna));

        assertThat(deleted.failure()).isNull();
        assertThat(rows("link_token", anna)).isZero();
        assertThat(exists(anna)).isFalse();
    }

    @Test
    void deletionWaitingForChannelUpdateRemovesThePreference() throws Exception {
        var anna = account();

        var deleted = locks.whileRowIsHeld(
                () -> preferences.update(new UpdateMyChannels(false, true), anna), () -> accounts.delete(anna));

        assertThat(deleted.failure()).isNull();
        assertThat(rows("preference", anna)).isZero();
        assertThat(exists(anna)).isFalse();
    }

    @Test
    void mergeWaitingForLinkOfRemovedAccountRemovesItsToken() throws Exception {
        var removed = account();
        var survivor = account();
        var source = card(removed);
        var target = card(survivor);
        var removedActor = Actor.of(removed.accountId(), source, Map.of());

        var merged = locks.whileRowIsHeld(
                () -> preferences.createTelegramLink(removedActor),
                () -> clients.merge(source, target, Actor.master()));

        assertThat(merged.failure()).isNull();
        assertThat(rows("link_token", removed)).isZero();
        assertThat(exists(removed)).isFalse();
        assertThat(exists(survivor)).isTrue();
    }

    @Test
    void secondDeletionOfTheSameAccountFindsNothing() throws Exception {
        var anna = account();

        var second = locks.whileRowIsHeld(() -> accounts.delete(anna), () -> accounts.delete(anna));

        assertThat(second.failure()).isInstanceOf(AccountNotFoundException.class);
        assertThat(exists(anna)).isFalse();
    }

    /** Аккаунт входа без карточки, ролей и способов входа — строка напрямую. */
    private Actor account() {
        var accountId = UUID.randomUUID();
        accountIds.add(accountId);
        var now = Timestamp.from(Instant.now());
        jdbc.sql("insert into identity.account (id, created_at, last_login_at) values (?, ?, ?)")
                .params(accountId, now, now)
                .update();
        return Actor.of(accountId, null, Map.of());
    }

    /** Карточка клиентки с аккаунтом {@code actor}. */
    private UUID card(Actor actor) {
        var id = clients.register(new RegisterClient(actor.accountId(), "Анна", null, null))
                .id();
        clientIds.add(id);
        return id;
    }

    private long rows(String table, Actor actor) {
        return jdbc.sql("select count(*) from notifications." + table + " where account_id = ?")
                .param(actor.accountId())
                .query(Long.class)
                .single();
    }

    private boolean exists(Actor actor) {
        return jdbc.sql("select count(*) from identity.account where id = ?")
                        .param(actor.accountId())
                        .query(Long.class)
                        .single()
                > 0;
    }
}
