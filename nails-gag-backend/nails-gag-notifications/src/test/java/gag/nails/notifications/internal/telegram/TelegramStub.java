package gag.nails.notifications.internal.telegram;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bot API Telegram для тестов — HTTP-сервер на {@code 127.0.0.1} со случайным портом: настоящий клиент модуля ходит в
 * него вместо {@code api.telegram.org}. Запоминает каждый запрос (метод Bot API, токен из адреса, тело JSON) и отвечает
 * по сценарию метода: заданные ответы по очереди, потом — ответ по умолчанию ({@code sendMessage} — принято,
 * {@code getUpdates} — пусто). Ответ может «зависнуть» — сервер молчит, пока тест не отпустит его ({@link #reset()},
 * {@link #close()}) или не пройдёт заданное время.
 */
public final class TelegramStub implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<Reply>> scripts = new ConcurrentHashMap<>();
    private final Map<Long, Queue<Reply>> chatScripts = new ConcurrentHashMap<>();
    private volatile CountDownLatch released = new CountDownLatch(1);

    private TelegramStub(HttpServer server) {
        this.server = server;
    }

    /** Запущенный сервер; остановить — {@link #close()}. */
    public static TelegramStub started() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var stub = new TelegramStub(server);
            server.createContext("/", stub::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return stub;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Адрес Bot API для настроек: {@code http://127.0.0.1:<порт>}. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Следующие ответы методу {@code method} — по порядку; когда кончатся, снова ответ по умолчанию. */
    public void respond(String method, Reply... replies) {
        var queue = scripts.computeIfAbsent(method, key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addAll(List.of(replies));
        }
    }

    /**
     * Следующие ответы на {@code sendMessage} в чат {@code chatId} — по порядку, раньше сценария метода: прогон
     * отправки шлёт и чужие созревшие доставки, а им сценарий теста не предназначен.
     */
    public void respondToChat(long chatId, Reply... replies) {
        var queue = chatScripts.computeIfAbsent(chatId, key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addAll(List.of(replies));
        }
    }

    /** Запросы к методу {@code method} в порядке поступления. */
    public List<Request> requests(String method) {
        return requests.stream()
                .filter(request -> request.method().equals(method))
                .toList();
    }

    /** Запросы {@code sendMessage} в чат {@code chatId}. */
    public List<Request> messagesTo(long chatId) {
        return requests("sendMessage").stream()
                .filter(request -> request.body().path("chat_id").asLong() == chatId)
                .toList();
    }

    /** Ждёт, пока методу {@code method} не придёт {@code count} запросов. */
    public List<Request> await(String method, int count) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (requests(method).size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + count + " " + method + " requests, got " + requests(method));
            }
            pause();
        }
        return requests(method);
    }

    /** Забывает запросы и сценарии, отпускает зависшие ответы. */
    public void reset() {
        requests.clear();
        scripts.clear();
        chatScripts.clear();
        var previous = released;
        released = new CountDownLatch(1);
        previous.countDown();
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var path = exchange.getRequestURI().getPath();
            var slash = path.lastIndexOf('/');
            var token = path.substring("/bot".length(), slash);
            var method = path.substring(slash + 1);
            var body = exchange.getRequestBody().readAllBytes();
            var json = body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            var request = new Request(
                    method, token, json, exchange.getRequestHeaders().getFirst("Content-Type"));
            requests.add(request);
            var reply = next(method, json);
            if (reply.hang() != null) {
                hang(reply.hang());
                return;
            }
            var answer = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), answer.length);
            exchange.getResponseBody().write(answer);
        }
    }

    private Reply next(String method, JsonNode body) {
        if (method.equals("sendMessage")) {
            var chat = chatScripts.get(body.path("chat_id").asLong());
            if (chat != null) {
                synchronized (chat) {
                    var scripted = chat.poll();
                    if (scripted != null) {
                        return scripted;
                    }
                }
            }
        }
        var queue = scripts.get(method);
        if (queue != null) {
            synchronized (queue) {
                var scripted = queue.poll();
                if (scripted != null) {
                    return scripted;
                }
            }
        }
        return method.equals("getUpdates") ? Reply.updates("[]") : Reply.ok("{\"message_id\": 1}");
    }

    private void hang(Duration duration) {
        try {
            released.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Запрос к Bot API.
     *
     * @param method метод — последний сегмент пути
     * @param token токен бота из пути {@code /bot<токен>/<метод>}
     * @param body тело JSON
     * @param contentType заголовок {@code Content-Type}
     */
    public record Request(
            String method,
            String token,
            JsonNode body,
            @Nullable String contentType) {}

    /**
     * Ответ сервера.
     *
     * @param status код HTTP
     * @param body тело
     * @param contentType тип тела
     * @param hang не отвечать столько времени (или пока тест не отпустит); пусто — ответить сразу
     */
    public record Reply(
            int status,
            String body,
            String contentType,
            @Nullable Duration hang) {

        /** Успех с результатом {@code result} (JSON). */
        public static Reply ok(String result) {
            return new Reply(200, "{\"ok\": true, \"result\": " + result + "}", "application/json", null);
        }

        /** Успех {@code getUpdates} с массивом обновлений {@code updates} (JSON). */
        public static Reply updates(String updates) {
            return ok(updates);
        }

        /** Ошибка Bot API: {@code ok: false}, код и описание, у 429 — {@code retry_after}. */
        public static Reply error(int status, String description, @Nullable Integer retryAfter) {
            var parameters = retryAfter == null ? "" : ", \"parameters\": {\"retry_after\": " + retryAfter + "}";
            return new Reply(
                    status,
                    "{\"ok\": false, \"error_code\": " + status + ", \"description\": \"" + description + "\""
                            + parameters + "}",
                    "application/json",
                    null);
        }

        /** Ответ не Bot API: код и HTML прокси. */
        public static Reply html(int status) {
            return new Reply(status, "<html><body>Bad Gateway</body></html>", "text/html", null);
        }

        /** Молчать, пока тест не отпустит, но не дольше {@code duration}. */
        public static Reply hang(Duration duration) {
            return new Reply(200, "", "application/json", duration);
        }
    }
}
