package gag.nails.notifications.internal.telegram;

import gag.nails.common.actor.Actor;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.NotificationsModuleTest;
import gag.nails.notifications.internal.telegram.TelegramApi.Chat;
import gag.nails.notifications.internal.telegram.TelegramApi.Message;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import gag.nails.notifications.internal.telegram.TelegramApi.User;
import gag.nails.testsupport.MutableClock;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Тесты бота в контексте модуля: бот настроен (токен и имя), Bot API — заглушка {@link TelegramStub} одна на все
 * классы-наследники (и контекст у них один). Приём обновлений по расписанию в тестах выключен — обновления
 * обрабатываются вызовом {@link TelegramUpdates#handle}. Аккаунты — строки {@code identity.account} напрямую (ссылку
 * выдают только существующему аккаунту). После теста удаляются ссылки, привязки и настройки каналов своих аккаунтов,
 * сами аккаунты и привязки своих чатов.
 */
@NotificationsModuleTest
@TestPropertySource(
        properties = {
            "nails-gag.notifications.telegram.bot-token=" + TelegramTestSupport.TOKEN,
            "nails-gag.notifications.telegram.bot-username=" + TelegramTestSupport.BOT
        })
abstract class TelegramTestSupport {

    static final String TOKEN = "424242:context-secret-token";
    static final String BOT = "nails_gag_bot";

    static final String LINKED = "Готово: уведомления nails.gag будут приходить сюда.";
    static final String EXPIRED = "Ссылка устарела, получите новую в личном кабинете.";
    static final String STOPPED = "Уведомления отключены. Подключить снова можно в личном кабинете.";
    static final String HINT = "Я только присылаю уведомления о записи. Написать мастеру: @gagushichewa.";
    static final String HELP =
            "Чтобы получать сюда уведомления о записи, подключите бота в личном кабинете: https://nails-gag.ru/account";

    static final TelegramStub STUB = TelegramStub.started();

    private static final AtomicLong UPDATE_IDS = new AtomicLong(1000);

    @DynamicPropertySource
    static void botApi(DynamicPropertyRegistry registry) {
        registry.add("nails-gag.notifications.telegram.api-url", STUB::url);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TelegramUpdates updates;

    @Autowired
    NotificationPreferences preferences;

    private final List<UUID> accounts = new ArrayList<>();
    private final List<Long> chats = new ArrayList<>();

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @AfterEach
    void removeOwn() {
        if (!accounts.isEmpty()) {
            for (var table : List.of("link_token", "telegram_chat", "preference")) {
                jdbc.sql("delete from notifications." + table + " where account_id in (:ids)")
                        .param("ids", accounts)
                        .update();
            }
        }
        if (!accounts.isEmpty()) {
            jdbc.sql("delete from identity.account where id in (:ids)")
                    .param("ids", accounts)
                    .update();
        }
        if (!chats.isEmpty()) {
            jdbc.sql("delete from notifications.telegram_chat where chat_id in (:chats)")
                    .param("chats", chats)
                    .update();
        }
        STUB.reset();
    }

    /** Свой аккаунт клиентки (строка напрямую, без карточки и способов входа) — сессия с ним. */
    Actor account() {
        var accountId = UUID.randomUUID();
        accounts.add(accountId);
        jdbc.sql("insert into identity.account (id, created_at, last_login_at) values (?, ?, ?)")
                .params(accountId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()))
                .update();
        return Actor.of(accountId, null, Map.of());
    }

    /** Свой чат Telegram — случайный id. */
    long chat() {
        var chat = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        chats.add(chat);
        return chat;
    }

    /** Токен новой ссылки привязки аккаунта {@code actor}. */
    String linkToken(Actor actor) {
        var url = preferences.createTelegramLink(actor).url().toString();
        return url.substring(url.indexOf("?start=") + "?start=".length());
    }

    /** Привязка строкой напрямую — как после {@code /start}. */
    void bind(Actor actor, long chat) {
        jdbc.sql("insert into notifications.telegram_chat (id, account_id, chat_id, linked_at) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), actor.accountId(), chat, Timestamp.from(clock.instant()))
                .update();
    }

    /** Чат, привязанный к аккаунту. */
    Optional<Long> chatOf(Actor actor) {
        return jdbc.sql("select chat_id from notifications.telegram_chat where account_id = ?")
                .param(actor.accountId())
                .query(Long.class)
                .optional();
    }

    /** Аккаунт, к которому привязан чат. */
    Optional<UUID> accountOf(long chat) {
        return jdbc.sql("select account_id from notifications.telegram_chat where chat_id = ?")
                .param(chat)
                .query(UUID.class)
                .optional();
    }

    long linkTokens(Actor actor) {
        return jdbc.sql("select count(*) from notifications.link_token where account_id = ?")
                .param(actor.accountId())
                .query(Long.class)
                .single();
    }

    /** Ответы бота в чат {@code chat} — тексты {@code sendMessage} по порядку. */
    static List<String> replies(long chat) {
        return STUB.messagesTo(chat).stream()
                .map(request -> request.body().path("text").asString())
                .toList();
    }

    /** Сообщение человека в личном чате с ботом. */
    static Update privateMessage(long chat, @Nullable String text) {
        return message(chat, "private", false, text);
    }

    static Update message(long chat, String type, boolean fromBot, @Nullable String text) {
        return new Update(
                UPDATE_IDS.incrementAndGet(), new Message(new User(chat, fromBot), new Chat(chat, type), text));
    }
}
