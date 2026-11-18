package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * Клиент Bot API на {@link MockRestServiceServer}: тела запросов, разбор ответов и ошибок. Ошибка не несёт адреса
 * запроса (в нём токен бота) и исходного исключения клиента HTTP — только метод, код ответа и класс сбоя.
 */
@ExtendWith(OutputCaptureExtension.class)
class TelegramApiTest {

    static final String TOKEN = "123456:secret-token";
    static final String API = "https://api.telegram.org/bot" + TOKEN + "/";

    private final MockRestServiceServer server;
    private final TelegramApi api;

    TelegramApiTest() {
        var builder = TelegramApi.client("https://api.telegram.org", TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        api = new TelegramApi(builder.build(), builder.build(), null);
    }

    @Test
    void sendMessageIsPlainTextWithoutLinkPreview() {
        server.expect(requestTo(API + "sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                                {"chat_id": 4242, "text": "Вы записаны\\nhttps://nails-gag.ru/account",
                                 "link_preview_options": {"is_disabled": true}}
                                """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"ok\": true, \"result\": {\"message_id\": 7}}", MediaType.APPLICATION_JSON));

        api.sendMessage(4242, "Вы записаны\nhttps://nails-gag.ru/account");

        server.verify();
    }

    @Test
    void getUpdatesAsksForMessagesAfterOffset() {
        server.expect(requestTo(API + "getUpdates"))
                .andExpect(
                        content().json("{\"timeout\": 50, \"allowed_updates\": [\"message\"]}", JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"ok\": true, \"result\": []}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "getUpdates"))
                .andExpect(content()
                        .json(
                                "{\"offset\": 18, \"timeout\": 50, \"allowed_updates\": [\"message\"]}",
                                JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"ok": true, "result": [
                          {"update_id": 18, "message": {"message_id": 3, "date": 1790000000,
                            "from": {"id": 77, "is_bot": false, "first_name": "Анна", "language_code": "ru"},
                            "chat": {"id": 77, "type": "private", "first_name": "Анна"},
                            "text": "/start abc", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]}},
                          {"update_id": 19, "edited_message": {"message_id": 4}}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(api.getUpdates(null, Duration.ofSeconds(50))).isEmpty();
        var updates = api.getUpdates(18L, Duration.ofSeconds(50));

        assertThat(updates).extracting(Update::updateId).containsExactly(18L, 19L);
        var message = updates.getFirst().message();
        assertThat(message).isNotNull();
        assertThat(message.text()).isEqualTo("/start abc");
        assertThat(message.chat()).isEqualTo(new TelegramApi.Chat(77, "private"));
        assertThat(message.from()).isEqualTo(new TelegramApi.User(77, false));
        assertThat(updates.get(1).message()).isNull();
        server.verify();
    }

    @Test
    void errorCarriesStatusDescriptionAndRetryAfter() {
        server.expect(requestTo(API + "sendMessage"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 7",
                                 "parameters": {"retry_after": 7}}
                                """));
        server.expect(requestTo(API + "sendMessage"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"ok": false, "error_code": 403,
                                 "description": "Forbidden: bot was blocked by the user"}
                                """));

        assertThatThrownBy(() -> api.sendMessage(1, "текст")).isInstanceOfSatisfying(TelegramApiException.class, e -> {
            assertThat(e.status()).isEqualTo(429);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
            assertThat(e.getMessage()).isEqualTo("telegram sendMessage failed: HTTP 429");
        });
        assertThatThrownBy(() -> api.sendMessage(1, "текст")).isInstanceOfSatisfying(TelegramApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.retryAfter()).isNull();
            assertThat(e.description()).isEqualTo("Forbidden: bot was blocked by the user");
            assertThat(e.getMessage()).doesNotContain("blocked");
        });
    }

    @Test
    void answerThatIsNotBotApiStillGivesStatus() {
        server.expect(requestTo(API + "getUpdates"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>Bad Gateway</html>"));
        server.expect(requestTo(API + "getUpdates"))
                .andRespond(withSuccess("{\"ok\": false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "getUpdates")).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        var messages = new ArrayList<String>();
        for (var i = 0; i < 3; i++) {
            assertThatThrownBy(() -> api.getUpdates(null, Duration.ofSeconds(50)))
                    .isInstanceOfSatisfying(TelegramApiException.class, e -> {
                        assertThat(e.answered()).isTrue();
                        messages.add(e.getMessage());
                    });
        }
        assertThat(messages)
                .containsExactly(
                        "telegram getUpdates failed: HTTP 502",
                        "telegram getUpdates failed: HTTP 200",
                        "telegram getUpdates failed: unreadable answer (HTTP 200)");
    }

    /**
     * Обновление, которое не разбирается, не ломает пачку: остальные разобраны, у него — только id (чтобы
     * подтвердить); без читаемого id оно пропускается. Содержимое в лог не попадает.
     */
    @Test
    void malformedUpdateDoesNotBreakTheBatch(CapturedOutput output) {
        server.expect(requestTo(API + "getUpdates")).andRespond(withSuccess("""
                        {"ok": true, "result": [
                          {"update_id": 30, "message": {"chat": "secret-chat-text"}},
                          {"update_id": 31, "message": {"from": {"id": 5, "is_bot": false},
                            "chat": {"id": 5, "type": "private"}, "text": "/stop"}},
                          {"message": {"text": "secret-without-id"}},
                          "secret-not-an-object"
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var updates = api.getUpdates(null, Duration.ofSeconds(50));

        assertThat(updates).extracting(Update::updateId).containsExactly(30L, 31L);
        assertThat(updates.getFirst().message()).isNull();
        assertThat(updates.get(1).message()).isNotNull();
        assertThat(output.getAll())
                .contains("telegram update 30 is not readable")
                .doesNotContain("secret");
    }

    /** Отправка, принятая Telegram (200), — доставлена, даже если тело ответа не разобрать: повтор дал бы дубль. */
    @Test
    void acceptedMessageWithUnreadableAnswerIsSent() {
        server.expect(requestTo(API + "sendMessage")).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "sendMessage"))
                .andRespond(withSuccess("{\"ok\": true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "sendMessage")).andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        api.sendMessage(1, "текст");
        api.sendMessage(1, "текст");
        api.sendMessage(1, "текст");

        server.verify();
    }

    /** Подтверждение обработанных при остановке: {@code getUpdates} со смещением и нулевым таймаутом. */
    @Test
    void confirmAsksForNothingNew() {
        server.expect(requestTo(API + "getUpdates"))
                .andExpect(content()
                        .json(
                                "{\"offset\": 41, \"timeout\": 0, \"allowed_updates\": [\"message\"]}",
                                JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"ok\": true, \"result\": []}", MediaType.APPLICATION_JSON));

        api.confirm(41);

        server.verify();
    }

    @Test
    void ioErrorHidesTheAddressWithTheToken() {
        server.expect(requestTo(API + "sendMessage")).andRespond(withException(new ConnectException("refused")));
        server.expect(requestTo(API + "getUpdates")).andRespond(withException(new IOException("reset")));

        assertThatThrownBy(() -> api.sendMessage(1, "текст")).isInstanceOfSatisfying(TelegramApiException.class, e -> {
            assertThat(e.answered()).isFalse();
            assertThat(e.getMessage())
                    .isEqualTo("telegram sendMessage failed: no response (java.net.ConnectException)");
            assertThat(e.getCause()).isNull();
            assertThat(e.toString()).doesNotContain(TOKEN).doesNotContain("api.telegram.org");
        });
        assertThatThrownBy(() -> api.getUpdates(5L, Duration.ofSeconds(50)))
                .isInstanceOfSatisfying(TelegramApiException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain(TOKEN);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void disabledApiIsNotConfiguredAndRefusesCalls() {
        var disabled = TelegramApi.disabled();

        assertThat(disabled.configured()).isFalse();
        assertThat(api.configured()).isTrue();
        assertThatThrownBy(() -> disabled.sendMessage(1, "текст"))
                .isInstanceOf(InternalException.class)
                .isNotInstanceOf(TelegramApiException.class);
        assertThatThrownBy(() -> disabled.getUpdates(null, Duration.ofSeconds(50)))
                .isInstanceOf(InternalException.class);
    }
}
