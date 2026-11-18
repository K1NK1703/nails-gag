package gag.nails.notifications.internal.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import gag.nails.common.error.InternalException;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Клиент Bot API Telegram (спека §3.6): {@code sendMessage} и {@code getUpdates} по {@code <api-url>/bot<токен>/}.
 *
 * <p>Токен — секрет, а он часть адреса каждого запроса. Поэтому наблюдение (трассы, метрики с адресом) у этого клиента
 * выключено ({@link ObservationRegistry#NOOP}), а сбой — всегда {@link TelegramApiException} с методом и кодом
 * ответа: исключения клиента HTTP Spring (их текст начинается с адреса запроса) не выходят наружу и не становятся
 * причиной. Описание ошибки от Telegram в текст исключения не попадает. Тела запросов в отладочном логе клиента HTTP
 * Spring не показывают ни чат, ни текст ({@code toString} тел скрывает их).
 *
 * <p>Таймауты раздельные: отправка — коротко ({@link #SEND_TIMEOUT}), чтобы попытка доставки укладывалась в её аренду
 * (5 минут); долгий опрос — на {@link #POLL_MARGIN} дольше, чем Telegram держит {@code getUpdates} без новых сообщений.
 * Запрос прерывается прерыванием потока — так останавливается приём обновлений.
 */
final class TelegramApi implements AutoCloseable {

    /** Подключение к Bot API. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Ответ на {@code sendMessage}. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);

    /** Запас ответа на {@code getUpdates} сверх времени, которое Telegram держит запрос. */
    static final Duration POLL_MARGIN = Duration.ofSeconds(10);

    private static final String SEND_MESSAGE = "sendMessage";
    private static final String GET_UPDATES = "getUpdates";

    /** Только сообщения: правленые сообщения, кнопки, участники чатов боту не нужны. */
    private static final List<String> ALLOWED_UPDATES = List.of("message");

    private static final ParameterizedTypeReference<Reply<Object>> SENT = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Reply<List<Update>>> UPDATES =
            new ParameterizedTypeReference<>() {};

    private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);

    private final @Nullable RestClient sending;
    private final @Nullable RestClient polling;
    private final @Nullable HttpClient http;

    /**
     * @param sending клиент для {@code sendMessage} — с базовым адресом {@link #client}
     * @param polling клиент для {@code getUpdates} — с тем же адресом и таймаутом долгого опроса
     * @param http клиент HTTP обоих — закрывается вместе с этим; пусто — закрывать нечего
     */
    TelegramApi(@Nullable RestClient sending, @Nullable RestClient polling, @Nullable HttpClient http) {
        this.sending = sending;
        this.polling = polling;
        this.http = http;
    }

    /** Бот не настроен: запросов нет, вызовы — ошибка программы. */
    static TelegramApi disabled() {
        return new TelegramApi(null, null, null);
    }

    /** Клиент Bot API бота {@code token} по адресу {@code apiUrl} с таймаутами по умолчанию; Telegram держит опрос
     * {@code pollTimeout}. */
    static TelegramApi connect(String apiUrl, String token, Duration pollTimeout) {
        return connect(apiUrl, token, new Timeouts(CONNECT_TIMEOUT, SEND_TIMEOUT, pollTimeout.plus(POLL_MARGIN)));
    }

    /** Клиент Bot API бота {@code token} по адресу {@code apiUrl} (схема и хост без пути). */
    static TelegramApi connect(String apiUrl, String token, Timeouts timeouts) {
        var http = HttpClient.newBuilder().connectTimeout(timeouts.connect()).build();
        var sendFactory = new JdkClientHttpRequestFactory(http);
        sendFactory.setReadTimeout(timeouts.send());
        var pollFactory = new JdkClientHttpRequestFactory(http);
        pollFactory.setReadTimeout(timeouts.poll());
        var builder = client(apiUrl, token);
        return new TelegramApi(
                builder.clone().requestFactory(sendFactory).build(),
                builder.clone().requestFactory(pollFactory).build(),
                http);
    }

    /** Основа клиентов: базовый адрес бота, без наблюдения — адрес с токеном не уходит в трассы и метрики. */
    static RestClient.Builder client(String apiUrl, String token) {
        return RestClient.builder().baseUrl(apiUrl + "/bot" + token).observationRegistry(ObservationRegistry.NOOP);
    }

    /** Бот настроен: запросы уходят в Bot API. */
    boolean configured() {
        return sending != null;
    }

    /**
     * Простой текст в чат, без разметки ({@code parse_mode} не задан) и без превью ссылок.
     *
     * @throws TelegramApiException Telegram не принял сообщение или не ответил
     */
    void sendMessage(long chatId, String text) {
        call(require(sending), SEND_MESSAGE, new SendMessage(chatId, text, new LinkPreview(true)), SENT);
    }

    /**
     * Новые сообщения боту — долгий опрос: Telegram держит запрос до {@code timeout}, если сообщений нет.
     *
     * @param offset id первого ещё не обработанного обновления; меньшие Telegram считает обработанными и забывает;
     *     пусто — с самого старого необработанного
     * @throws TelegramApiException Telegram ответил ошибкой (в том числе 409 — опрашивает кто-то ещё) или не ответил
     */
    List<Update> getUpdates(@Nullable Long offset, Duration timeout) {
        return call(
                require(polling),
                GET_UPDATES,
                new GetUpdates(offset, Math.toIntExact(timeout.toSeconds()), ALLOWED_UPDATES),
                UPDATES);
    }

    /** Прерывает запросы в работе и освобождает клиент HTTP — не дожидаясь долгого опроса. */
    @Override
    public void close() {
        if (http != null) {
            http.shutdownNow();
        }
    }

    private static RestClient require(@Nullable RestClient client) {
        if (client == null) {
            throw new InternalException("telegram bot is not configured: no bot token");
        }
        return client;
    }

    private static <T> T call(
            RestClient client, String method, Object request, ParameterizedTypeReference<Reply<T>> type) {
        try {
            return client.post()
                    .uri("/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((sent, response) -> {
                        var status = response.getStatusCode().value();
                        var reply = read(method, response, type);
                        if (status == 200 && reply != null && Boolean.TRUE.equals(reply.ok())) {
                            var result = reply.result();
                            if (result != null) {
                                return result;
                            }
                        }
                        log.debug("telegram {} answered HTTP {}", method, status);
                        throw TelegramApiException.answered(
                                method,
                                status,
                                reply == null || reply.parameters() == null
                                        ? null
                                        : reply.parameters().retryAfter(),
                                reply == null ? null : reply.description());
                    });
        } catch (TelegramApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (!(e instanceof RestClientException)) {
                // Не сбой сети, а ошибка клиента (тело не записалось): без причины — её текст бывает с адресом.
                throw new InternalException(
                        "telegram " + method + " failed: " + e.getClass().getName());
            }
            var cause = NestedExceptionUtils.getMostSpecificCause(e);
            log.debug(
                    "telegram {} got no response: {}", method, cause.getClass().getName());
            throw TelegramApiException.unanswered(method, cause);
        }
    }

    /** Ответ Bot API; не JSON Bot API (страница прокси, обрыв посреди тела) — пусто. */
    private static <T> @Nullable Reply<T> read(
            String method,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            ParameterizedTypeReference<Reply<T>> type) {
        try {
            return response.bodyTo(type);
        } catch (RestClientException e) {
            log.debug(
                    "telegram {} answer is not readable: {}",
                    method,
                    e.getClass().getName());
            return null;
        }
    }

    /**
     * Таймауты клиента.
     *
     * @param connect подключение
     * @param send ответ на {@code sendMessage}
     * @param poll ответ на {@code getUpdates} — дольше, чем Telegram держит долгий опрос
     */
    record Timeouts(Duration connect, Duration send, Duration poll) {}

    /** Обновление: пока бот получает только сообщения, у остальных обновлений {@link #message()} пусто. */
    record Update(
            @JsonProperty("update_id") long updateId,
            @Nullable Message message) {}

    /**
     * Сообщение боту. {@link #toString()} не показывает ни текст, ни чат.
     *
     * @param from отправитель; пусто — сообщение от имени канала или группы
     * @param chat чат сообщения
     * @param text текст; пусто — не текст (стикер, фото)
     */
    record Message(
            @Nullable User from,
            @Nullable Chat chat,
            @Nullable String text) {

        @Override
        public String toString() {
            return "Message[from=" + (from == null ? "<none>" : "<set>") + ", chat="
                    + (chat == null ? "<none>" : chat.type()) + ", text=" + (text == null ? "<none>" : "<set>") + "]";
        }
    }

    /** Отправитель: человек или бот. */
    record User(long id, @JsonProperty("is_bot") boolean isBot) {

        @Override
        public String toString() {
            return "User[isBot=" + isBot + "]";
        }
    }

    /**
     * Чат.
     *
     * @param type {@code private} — личный чат с человеком; {@code group}, {@code supergroup}, {@code channel}
     */
    record Chat(long id, @Nullable String type) {

        @Override
        public String toString() {
            return "Chat[type=" + type + "]";
        }
    }

    /** Ответ Bot API: успех с результатом или ошибка с кодом, описанием и параметрами. */
    private record Reply<T>(
            @Nullable Boolean ok,
            @Nullable T result,
            @JsonProperty("error_code") @Nullable Integer errorCode,
            @Nullable String description,
            @Nullable Parameters parameters) {}

    /** Параметры ошибки: у 429 — сколько секунд ждать. */
    private record Parameters(
            @JsonProperty("retry_after") @Nullable Integer retryAfter) {}

    /** Тело {@code sendMessage}; {@link #toString()} (его пишет отладочный лог клиента HTTP) не показывает данных. */
    private record SendMessage(
            @JsonProperty("chat_id") long chatId,
            String text,
            @JsonProperty("link_preview_options") LinkPreview linkPreviewOptions) {

        @Override
        public String toString() {
            return "SendMessage[" + text.length() + " chars]";
        }
    }

    private record LinkPreview(@JsonProperty("is_disabled") boolean isDisabled) {}

    /** Тело {@code getUpdates}; без смещения ключа {@code offset} нет. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GetUpdates(
            @Nullable Long offset,
            int timeout,
            @JsonProperty("allowed_updates") List<String> allowedUpdates) {}
}
