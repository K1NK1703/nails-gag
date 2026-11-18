package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Обработка сообщений боту (спека §3.6): {@code /start <токен>} привязывает чат к аккаунту ссылки, {@code /stop}
 * отвязывает, остальное — подсказка; только личные чаты людей. Ответы уходят в заглушку Bot API.
 */
@ExtendWith(OutputCaptureExtension.class)
class TelegramUpdatesTest extends TelegramTestSupport {

    @Test
    void validTokenLinksTheChat() {
        var anna = account();
        var chat = chat();

        updates.handle(privateMessage(chat, "/start " + linkToken(anna)));

        assertThat(chatOf(anna)).contains(chat);
        assertThat(linkTokens(anna)).isZero();
        assertThat(replies(chat)).containsExactly(LINKED);
        assertThat(STUB.requests("sendMessage")).singleElement().satisfies(request -> {
            assertThat(request.token()).isEqualTo(TOKEN);
            assertThat(request.body().has("parse_mode")).isFalse();
        });
    }

    @Test
    void expiredTokenIsRefusedAndUsedUp() {
        var anna = account();
        var chat = chat();
        var token = linkToken(anna);
        clock.advance(Duration.ofMinutes(10));

        updates.handle(privateMessage(chat, "/start " + token));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(linkTokens(anna)).isZero();
        assertThat(replies(chat)).containsExactly(EXPIRED);
    }

    @Test
    void tokenIsValidUntilItsLastMoment() {
        var anna = account();
        var chat = chat();
        var token = linkToken(anna);
        clock.advance(Duration.ofMinutes(10).minusMillis(1));

        updates.handle(privateMessage(chat, "/start " + token));

        assertThat(chatOf(anna)).contains(chat);
    }

    @Test
    void unknownOrMalformedTokenIsRefused() {
        var anna = account();
        var chat = chat();
        var token = linkToken(anna);
        var unknown = "A".repeat(43);

        updates.handle(privateMessage(chat, "/start " + unknown));
        updates.handle(privateMessage(chat, "/start not-a-token"));
        updates.handle(privateMessage(chat, "/start " + token + "x"));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(accountOf(chat)).isEmpty();
        assertThat(linkTokens(anna)).isOne();
        assertThat(replies(chat)).containsExactly(EXPIRED, EXPIRED, EXPIRED);
    }

    @Test
    void replayedUpdateKeepsTheBinding() {
        var anna = account();
        var chat = chat();
        var start = privateMessage(chat, "/start " + linkToken(anna));

        updates.handle(start);
        updates.handle(start);

        assertThat(chatOf(anna)).contains(chat);
        assertThat(replies(chat)).containsExactly(LINKED, EXPIRED);
    }

    @Test
    void chatOfAnotherAccountMovesToTheNewOne() {
        var anna = account();
        var vera = account();
        var chat = chat();
        bind(vera, chat);

        updates.handle(privateMessage(chat, "/start " + linkToken(anna)));

        assertThat(accountOf(chat)).contains(anna.accountId());
        assertThat(chatOf(vera)).isEmpty();
    }

    @Test
    void previousChatOfTheAccountIsReplaced() {
        var anna = account();
        var oldChat = chat();
        var newChat = chat();
        bind(anna, oldChat);

        updates.handle(privateMessage(newChat, "/start " + linkToken(anna)));

        assertThat(chatOf(anna)).contains(newChat);
        assertThat(accountOf(oldChat)).isEmpty();
    }

    @Test
    void stopUnlinksTheChat() {
        var anna = account();
        var chat = chat();
        var stranger = chat();
        bind(anna, chat);

        updates.handle(privateMessage(chat, "/stop"));
        updates.handle(privateMessage(stranger, "/stop"));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(replies(chat)).containsExactly(STOPPED);
        assertThat(replies(stranger)).containsExactly(STOPPED);
    }

    @Test
    void groupsChannelsAndBotsAreIgnored() {
        var anna = account();
        var token = linkToken(anna);
        var group = chat();
        var bot = chat();

        updates.handle(message(group, "group", false, "/start " + token));
        updates.handle(message(group, "supergroup", false, "/start@" + BOT + " " + token));
        updates.handle(message(group, "channel", false, "привет"));
        updates.handle(message(bot, "private", true, "/start " + token));
        updates.handle(new TelegramApi.Update(1, null));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(linkTokens(anna)).isOne();
        assertThat(STUB.requests("sendMessage")).isEmpty();
    }

    @Test
    void otherMessagesGetTheHint() {
        var chat = chat();

        updates.handle(privateMessage(chat, "Здравствуйте, можно перенести запись?"));
        updates.handle(privateMessage(chat, null));
        updates.handle(privateMessage(chat, "/help"));
        updates.handle(privateMessage(chat, "/start@other_bot " + "A".repeat(43)));

        assertThat(replies(chat)).containsExactly(HINT, HINT, HINT, HINT);
    }

    @Test
    void startWithoutTokenExplainsHowToLink() {
        var chat = chat();

        updates.handle(privateMessage(chat, "/start"));
        updates.handle(privateMessage(chat, "  /start   "));

        assertThat(replies(chat)).containsExactly(HELP, HELP);
        assertThat(accountOf(chat)).isEmpty();
    }

    @Test
    void commandWithBotNameWorks() {
        var anna = account();
        var chat = chat();

        updates.handle(privateMessage(chat, "/start@" + BOT.toUpperCase() + " " + linkToken(anna)));

        assertThat(chatOf(anna)).contains(chat);
        assertThat(replies(chat)).containsExactly(LINKED);

        updates.handle(privateMessage(chat, "/stop@" + BOT));

        assertThat(chatOf(anna)).isEmpty();
    }

    @Test
    void failedReplyKeepsTheBindingAndHidesTheToken(CapturedOutput output) {
        var anna = account();
        var chat = chat();
        STUB.respondToChat(chat, Reply.error(403, "Forbidden: bot was blocked by the user", null), Reply.html(502));

        updates.handle(privateMessage(chat, "/start " + linkToken(anna)));
        updates.handle(privateMessage(chat, "привет"));

        assertThat(chatOf(anna)).contains(chat);
        assertThat(output.getOut())
                .contains("telegram reply not sent: telegram sendMessage failed: HTTP 403")
                .contains("telegram sendMessage failed: HTTP 502")
                .doesNotContain(TOKEN)
                .doesNotContain(Long.toString(chat));
    }
}
