package gag.nails.notifications.internal.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import gag.nails.common.error.InternalException;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Клиент Bot API Telegram (спека §3.6): {@code sendMessage} и {@code getUpdates} по {@code <api-url>/bot<токен>/}.
 *
 * <p>Токен — секрет, а он часть адреса каждого запроса. Поэтому наблюдение (трассы, метрики с адресом) у этого
 * клиента выключено ({@link ObservationRegistry#NOOP}), а сбой — всегда {@link TelegramApiException} с методом и
 * кодом ответа: исключения клиента HTTP Spring (их текст начинается с адреса запроса) не выходят наружу и не
 * становятся причиной. Описание ошибки от Telegram в текст исключения не попадает. Тела запросов в отладочном логе
 * клиента HTTP Spring не показывают ни чат, ни текст ({@code toString} тел скрывает их).
 *
 * <p>Таймауты раздельные: доставка — коротко ({@link #SEND_TIMEOUT}), чтобы попытка укладывалась в её аренду
 * (5 минут); долгий опрос — на {@link #POLL_MARGIN} дольше, чем Telegram держит {@code getUpdates} без новых
 * сообщений; ответы бота и подтверждение обновлений при остановке — ещё короче ({@link #REPLY_TIMEOUT}), чтобы приём
 * останавливался вовремя. Долгий опрос прерывается прерыванием потока — так останавливается приём обновлений.
 */
final class TelegramApi implements AutoCloseable {

    /** Подключение к Bot API. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Ответ на {@code sendMessage} доставки. */
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(20);

    /** Ответ на ответ бота человеку и на подтверждение обновлений при остановке. */
    static final Duration REPLY_TIMEOUT = Duration.ofSeconds(5);

    /** Запас ответа на {@code getUpdates} сверх времени, которое Telegram держит запрос. */
    static final Duration POLL_MARGIN = Duration.ofSeconds(10);

    private static final String SEND_MESSAGE = "sendMessage";
    private static final String GET_UPDATES = "getUpdates";
    private static final int OK = 200;

    /** Только сообщения: правленые сообщения, кнопки, участники чатов боту не нужны. */
    private static final List<String> ALLOWED_UPDATES = List.of("message");

    private static final ParameterizedTypeReference<Reply<Object>> SENT = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Reply<List<JsonNode>>> UPDATES =
            new ParameterizedTypeReference<>() {};

    /** Разбор отдельных обновлений: неизвестные поля пропускаются. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger log = LoggerFactory.getLogger(TelegramApi.class);

    private final @Nullable RestClient sending;
    private final @Nullable RestClient polling;
    private final @Nullable RestClient replying;
    private final @Nullable HttpClient http;

    /**
     * @param sending клиент для {@code sendMessage} доставок — с базовым адресом {@link #client}
     * @param polling клиент для долгого опроса {@code getUpdates} — с тем же адресом
     * @param replying клиент для ответов бота и подтверждения обновлений — с тем же адресом
     * @param http клиент HTTP всех трёх — закрывается вместе с этим; пусто — закрывать нечего
     */
    TelegramApi(
            @Nullable RestClient sending,
            @Nullable RestClient polling,
            @Nullable RestClient replying,
            @Nullable HttpClient http) {
        this.sending = sending;
        this.polling = polling;
        this.replying = replying;
        this.http = http;
    }

    /** Для тестов: доставки и ответы бота — одним клиентом. */
    TelegramApi(RestClient sending, RestClient polling, @Nullable HttpClient http) {
        this(sending, polling, sending, http);
    }

    /** Бот не настроен: запросов нет, вызовы — ошибка программы. */
    static TelegramApi disabled() {
        return new TelegramApi(null, null, null, null);
    }

    /**
     * Клиент Bot API бота {@code token} по адресу {@code apiUrl} с таймаутами по умолчанию.
     *
     * @param pollTimeout сколько Telegram держит долгий опрос без новых сообщений
     */
    static TelegramApi connect(String apiUrl, String token, Duration pollTimeout) {
        return connect(
                apiUrl,
                token,
                new Timeouts(CONNECT_TIMEOUT, SEND_TIMEOUT, pollTimeout.plus(POLL_MARGIN), REPLY_TIMEOUT));
    }

    /** Клиент Bot API бота {@code token} по адресу {@code apiUrl} (схема и хост без пути). */
    static TelegramApi connect(String apiUrl, String token, Timeouts timeouts) {
        var http = HttpClient.newBuilder().connectTimeout(timeouts.connect()).build();
        var builder = client(apiUrl, token);
        return new TelegramApi(
                builder.clone().requestFactory(factory(http, timeouts.send())).build(),
                builder.clone().requestFactory(factory(http, timeouts.poll())).build(),
                builder.clone().requestFactory(factory(http, timeouts.reply())).build(),
                http);
    }

    /** Основа клиентов: базовый адрес бота, без наблюдения — адрес с токеном не уходит в трассы и метрики. */
    static RestClient.Builder client(String apiUrl, String token) {
        return RestClient.builder().baseUrl(apiUrl + "/bot" + token).observationRegistry(ObservationRegistry.NOOP);
    }

    private static JdkClientHttpRequestFactory factory(HttpClient http, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** Бот настроен: запросы уходят в Bot API. */
    boolean configured() {
        return sending != null;
    }

    /**
     * Сообщение доставки: простой текст в чат, без разметки ({@code parse_mode} не задан) и без превью ссылок. Ответ
     * 200, который не разобрать (или без результата), — принято: повтор отправил бы сообщение второй раз.
     *
     * @throws TelegramApiException Telegram не принял сообщение или не ответил
     */
    void sendMessage(long chatId, String text) {
        send(require(sending), chatId, text);
    }

    /** Ответ бота человеку — как {@link #sendMessage}, но с коротким таймаутом: его ждёт остановка приёма. */
    void reply(long chatId, String text) {
        send(require(replying), chatId, text);
    }

    /**
     * Новые сообщения боту — долгий опрос: Telegram держит запрос до {@code timeout}, если сообщений нет. Обновление,
     * которое не разбирается, приходит без сообщения (только его id — чтобы подтвердить); без читаемого id —
     * пропускается.
     *
     * @param offset id первого ещё не обработанного обновления; меньшие Telegram считает обработанными и забывает;
     *     пусто — с самого старого необработанного
     * @throws TelegramApiException Telegram ответил ошибкой (в том числе 409 — опрашивает кто-то ещё), ответ не
     *     разобрать или ответа нет
     */
    List<Update> getUpdates(@Nullable Long offset, Duration timeout) {
        return updates(require(polling), offset, timeout);
    }

    /**
     * Подтверждает обработанные обновления (меньше {@code offset}) без ожидания новых: {@code getUpdates} с нулевым
     * таймаутом и коротким ожиданием ответа. Полученные при этом обновления не обрабатываются — Telegram отдаст их
     * снова.
     *
     * @throws TelegramApiException Telegram ответил ошибкой или не ответил
     */
    void confirm(long offset) {
        updates(require(replying), offset, Duration.ZERO);
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

    private static void send(RestClient client, long chatId, String text) {
        var answer = exchange(client, SEND_MESSAGE, new SendMessage(chatId, text, new LinkPreview(true)), SENT);
        var reply = answer.reply();
        if (answer.status() == OK && (reply == null || !Boolean.FALSE.equals(reply.ok()))) {
            if (reply == null || reply.result() == null) {
                log.debug("telegram sendMessage accepted (HTTP 200) without a readable result");
            }
            return;
        }
        throw answer.failure(SEND_MESSAGE);
    }

    private static List<Update> updates(RestClient client, @Nullable Long offset, Duration timeout) {
        var answer = exchange(
                client,
                GET_UPDATES,
                new GetUpdates(offset, Math.toIntExact(timeout.toSeconds()), ALLOWED_UPDATES),
                UPDATES);
        var reply = answer.reply();
        if (answer.status() != OK || reply == null || !Boolean.TRUE.equals(reply.ok()) || reply.result() == null) {
            throw answer.failure(GET_UPDATES);
        }
        var updates = new ArrayList<Update>();
        for (var node : reply.result()) {
            var update = update(node);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    /** Обновление из JSON; не разбирается — только его id без сообщения; нет и id — пусто. Содержимое не в логе. */
    private static @Nullable Update update(JsonNode node) {
        try {
            return JSON.treeToValue(node, Update.class);
        } catch (JacksonException e) {
            var id = node.path("update_id");
            if (id.isIntegralNumber()) {
                log.warn(
                        "telegram update {} is not readable ({}), it is skipped",
                        id.asLong(),
                        e.getClass().getName());
                return new Update(id.asLong(), null);
            }
            log.warn(
                    "telegram update without a readable id is skipped ({})",
                    e.getClass().getName());
            return null;
        }
    }

    private static <T> Answer<T> exchange(
            RestClient client, String method, Object request, ParameterizedTypeReference<Reply<T>> type) {
        try {
            var answer = client.post()
                    .uri("/" + method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((sent, response) ->
                            new Answer<>(response.getStatusCode().value(), read(method, response, type)));
            if (answer == null) {
                throw new InternalException("telegram " + method + " exchange returned nothing");
            }
            if (answer.status() != OK) {
                log.debug("telegram {} answered HTTP {}", method, answer.status());
            }
            return answer;
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
     * @param send ответ на {@code sendMessage} доставки
     * @param poll ответ на долгий опрос {@code getUpdates} — дольше, чем Telegram его держит
     * @param reply ответ на ответ бота человеку и на подтверждение обновлений
     */
    record Timeouts(Duration connect, Duration send, Duration poll, Duration reply) {}

    /** Ответ сервера: код и разобранное тело; пусто — тело не Bot API. */
    private record Answer<T>(int status, @Nullable Reply<T> reply) {

        TelegramApiException failure(String method) {
            return TelegramApiException.answered(
                    method,
                    status,
                    reply != null,
                    reply == null || reply.parameters() == null
                            ? null
                            : reply.parameters().retryAfter(),
                    reply == null ? null : reply.description());
        }
    }

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
