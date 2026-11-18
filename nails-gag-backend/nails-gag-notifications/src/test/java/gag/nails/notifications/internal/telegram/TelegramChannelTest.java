package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;

/** Исход отправки по ответу Bot API ({@link MockRestServiceServer}): коды ошибок журнала доставок и отвязка чата. */
class TelegramChannelTest {

    private static final String SEND = TelegramApiTest.API + "sendMessage";
    private static final ResolvedRecipient ANNA = new ResolvedRecipient(null, 4242L, "Анна");
    private static final RenderedText TEXT = RenderedText.of("Вы записаны на пн, 5 октября, 14:00\nАнна, ждём вас.");

    private final MockRestServiceServer server;
    private final TelegramChannel channel;

    TelegramChannelTest() {
        var builder = TelegramApi.client("https://api.telegram.org", TelegramApiTest.TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        channel = new TelegramChannel(new TelegramApi(builder.build(), builder.build(), null));
    }

    @Test
    void acceptedMessageIsSent() {
        expectSend()
                .andExpect(jsonPath("$.chat_id").value(4242))
                .andExpect(jsonPath("$.text").value(TEXT.body()))
                .andExpect(jsonPath("$.parse_mode").doesNotExist())
                .andRespond(withSuccess("{\"ok\": true, \"result\": {\"message_id\": 1}}", MediaType.APPLICATION_JSON));

        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Sent());
        assertThat(channel.channel()).isEqualTo(Channel.TELEGRAM);
        assertThat(channel.configured()).isTrue();
        server.verify();
    }

    @Test
    void blockedBotUnlinksTheChat() {
        respond(HttpStatus.FORBIDDEN, "Forbidden: bot was blocked by the user", null);
        respond(HttpStatus.FORBIDDEN, "Forbidden: user is deactivated", null);

        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Fail("telegram_blocked", true));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Fail("telegram_blocked", true));
    }

    @Test
    void missingChatUnlinksTheChatOtherRejectionsDoNot() {
        respond(HttpStatus.BAD_REQUEST, "Bad Request: chat not found", null);
        respond(HttpStatus.BAD_REQUEST, "Bad Request: message text is empty", null);

        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Fail("telegram_chat_not_found", true));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Fail("telegram_rejected", false));
    }

    @Test
    void floodLimitWaitsForRetryAfter() {
        respond(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests: retry after 35", 35);
        respond(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null);

        assertThat(channel.send(ANNA, TEXT))
                .isEqualTo(new SendResult.Retry(Duration.ofSeconds(35), "telegram_rate_limited"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_rate_limited"));
    }

    @Test
    void serverErrorsAndSilenceAreRetried() {
        respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null);
        expectSend()
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html/>").contentType(MediaType.TEXT_HTML));
        expectSend().andRespond(withException(new IOException("connection reset")));
        expectSend().andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_server_error"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_server_error"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_unreachable"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_unreachable"));
    }

    @Test
    void wrongTokenAndOtherAnswersAreRetried() {
        respond(HttpStatus.UNAUTHORIZED, "Unauthorized", null);
        respond(HttpStatus.NOT_FOUND, "Not Found", null);
        respond(HttpStatus.CONFLICT, "Conflict", null);
        expectSend().andRespond(withSuccess("{\"ok\": false}", MediaType.APPLICATION_JSON));

        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_auth_failed"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_auth_failed"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_error"));
        assertThat(channel.send(ANNA, TEXT)).isEqualTo(new SendResult.Retry(null, "telegram_error"));
    }

    @Test
    void longTextIsCutToTelegramLimit() {
        var body = "Вы записаны\n" + "ж".repeat(4090) + "😀" + "конец";
        expectSend()
                .andExpect(jsonPath("$.text").value(TelegramChannel.fit(body)))
                .andRespond(withSuccess("{\"ok\": true, \"result\": {}}", MediaType.APPLICATION_JSON));

        assertThat(TelegramChannel.fit(body))
                .hasSize(TelegramChannel.MAX_TEXT_LENGTH)
                .endsWith("ж…");
        assertThat(TelegramChannel.fit("короткий")).isEqualTo("короткий");
        assertThat(channel.send(ANNA, RenderedText.of(body))).isEqualTo(new SendResult.Sent());
        server.verify();
    }

    @Test
    void surrogatePairIsNotSplit() {
        var body = "ж".repeat(TelegramChannel.MAX_TEXT_LENGTH - 2) + "😀😀";

        assertThat(TelegramChannel.fit(body))
                .hasSize(TelegramChannel.MAX_TEXT_LENGTH - 1)
                .endsWith("ж…");
    }

    @Test
    void unconfiguredChannelSendsNothing() {
        var disabled = new TelegramChannel(TelegramApi.disabled());

        assertThat(disabled.configured()).isFalse();
        assertThatThrownBy(() -> disabled.send(ANNA, TEXT)).isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT))
                .isInstanceOf(InternalException.class);
    }

    private ResponseActions expectSend() {
        return server.expect(requestTo(SEND));
    }

    private void respond(HttpStatus status, String description, @Nullable Integer retryAfter) {
        var parameters = retryAfter == null ? "" : ", \"parameters\": {\"retry_after\": " + retryAfter + "}";
        expectSend()
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\": false, \"error_code\": " + status.value() + ", \"description\": \""
                                + description + "\"" + parameters + "}"));
    }
}
