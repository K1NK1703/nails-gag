package gag.nails.notifications.internal.telegram;

import gag.nails.common.error.InternalException;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Запрос к Bot API не удался: Telegram ответил ошибкой или не ответил вовсе. Текст — только метод Bot API, код ответа
 * или класс сбоя сети: ни адреса запроса (в нём токен бота), ни описания ошибки от Telegram (оно бывает с данными
 * чата), ни исходного исключения клиента HTTP (его текст содержит адрес) — исключение можно писать в лог как есть.
 */
final class TelegramApiException extends InternalException {

    /** Код ответа HTTP; {@code 0} — ответа нет (сеть, таймаут, прерывание). */
    private final int status;

    private final @Nullable Duration retryAfter;
    private final @Nullable String description;

    private TelegramApiException(
            String message, int status, @Nullable Duration retryAfter, @Nullable String description) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
        this.description = description;
    }

    /**
     * Telegram ответил, но не успехом с результатом.
     *
     * @param readable тело — ответ Bot API; нет — у ответа 200 своё сообщение («unreadable answer»)
     */
    static TelegramApiException answered(
            String method,
            int status,
            boolean readable,
            @Nullable Integer retryAfterSeconds,
            @Nullable String description) {
        var what = status == 200 && !readable ? "unreadable answer (HTTP 200)" : "HTTP " + status;
        return new TelegramApiException(
                "telegram " + method + " failed: " + what,
                status,
                retryAfterSeconds == null || retryAfterSeconds < 0 ? null : Duration.ofSeconds(retryAfterSeconds),
                description);
    }

    /** Ответа нет; {@code failure} — самая глубокая причина сбоя клиента HTTP, в текст идёт только её класс. */
    static TelegramApiException unanswered(String method, Throwable failure) {
        return new TelegramApiException(
                "telegram " + method + " failed: no response ("
                        + failure.getClass().getName() + ")",
                0,
                null,
                null);
    }

    /** Код ответа HTTP; {@code 0} — ответа нет. */
    int status() {
        return status;
    }

    /** Telegram ответил (пусть и ошибкой). */
    boolean answered() {
        return status != 0;
    }

    /** Сколько ждать до следующего запроса (429 {@code parameters.retry_after}); пусто — Telegram не сказал. */
    @Nullable
    Duration retryAfter() {
        return retryAfter;
    }

    /** Описание ошибки от Telegram — только для разбора исхода, не для лога. */
    @Nullable
    String description() {
        return description;
    }

    /** Чат не найден (400 «Bad Request: chat not found»): писать в него боту больше некуда. */
    boolean chatNotFound() {
        return status == 400
                && description != null
                && description.toLowerCase(Locale.ROOT).contains("chat not found");
    }
}
