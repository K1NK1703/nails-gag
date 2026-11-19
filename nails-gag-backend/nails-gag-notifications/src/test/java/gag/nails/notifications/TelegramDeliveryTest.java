package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.notifications.internal.dispatch.Dispatcher;
import gag.nails.notifications.internal.telegram.TelegramStub;
import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Сообщение через настоящую отправку и настоящий канал Telegram: запись → сообщение → доставка {@code TELEGRAM SENT} →
 * {@code sendMessage} в привязанный чат на заглушке Bot API. Почта в этом контексте не настроена. Бот заблокирован
 * (403) — доставка неудачна, привязка чата снята. Часы — четверг 1 октября 2026, 09:00 по Москве.
 */
@NotificationsModuleTest
@TestPropertySource(
        properties = {
            "nails-gag.notifications.telegram.bot-token=" + TelegramDeliveryTest.TOKEN,
            "nails-gag.notifications.telegram.bot-username=nails_gag_bot"
        })
class TelegramDeliveryTest extends QueueTestSupport {

    static final String TOKEN = "313131:delivery-secret-token";

    /** Заглушка запускается до контекста: её адрес — в настройках бота. */
    private static final TelegramStub BOT_API = TelegramStub.started();

    @DynamicPropertySource
    static void botApi(DynamicPropertyRegistry registry) {
        registry.add("nails-gag.notifications.telegram.api-url", BOT_API::url);
    }

    @AfterAll
    static void stopBotApi() {
        BOT_API.close();
    }

    @Autowired
    Dispatcher dispatcher;

    @AfterEach
    void resetBotApi() {
        BOT_API.reset();
    }

    @Test
    void linkedChatReceivesBookingMessage() {
        var anna = registeredClient("Анна", "client-" + UUID.randomUUID() + "@example.com");
        var chat = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        linkTelegram(anna.accountId(), chat);
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(booking.id())).containsExactly(tuple("TELEGRAM", "SENT", 1, null));
        assertThat(BOT_API.messagesTo(chat)).singleElement().satisfies(request -> {
            assertThat(request.token()).isEqualTo(TOKEN);
            assertThat(request.body().path("text").asString())
                    .startsWith("Вы записаны на пн, 5 октября, 14:00\nАнна, мастер записал вас на визит.")
                    .contains("\nУслуги: Маникюр\n");
            assertThat(request.body().has("parse_mode")).isFalse();
        });
    }

    @Test
    void blockedBotFailsDeliveryAndUnlinksTheChat() {
        var anna = registeredClient("Анна", "client-" + UUID.randomUUID() + "@example.com");
        var chat = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        linkTelegram(anna.accountId(), chat);
        BOT_API.respondToChat(chat, Reply.error(403, "Forbidden: bot was blocked by the user", null));
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "15:00"), false);

        dispatcher.run();

        assertThat(deliveries(booking.id())).containsExactly(tuple("TELEGRAM", "FAILED", 1, "telegram_blocked"));
        assertThat(jdbc.sql("select count(*) from notifications.telegram_chat where account_id = ?")
                        .param(anna.accountId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private List<Tuple> deliveries(UUID bookingId) {
        return jdbc.sql("""
                        select d.channel, d.status, d.attempts, d.error_code from notifications.delivery d
                        join notifications.message m on m.id = d.message_id
                        where m.booking_id = ? and m.kind = 'BOOKED_BY_MASTER'
                        """)
                .param(bookingId)
                .query((row, number) -> tuple(
                        row.getString("channel"),
                        row.getString("status"),
                        row.getInt("attempts"),
                        row.getString("error_code")))
                .list();
    }
}
