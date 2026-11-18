package gag.nails.notifications.internal.telegram;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.dispatch.NotificationChannel;
import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;

/**
 * Сообщения в привязанный чат Telegram (спека §3.6): весь текст простым текстом, ссылки — обычными адресами. Настроен,
 * если задан токен бота (имя бота при токене обязательно — его проверяют настройки).
 *
 * <p>Исход — по ответу Bot API:
 *
 * <ul>
 *   <li>{@value #BLOCKED} — 403: человек заблокировал бота или удалил аккаунт; доставка неудачна, привязка чата
 *       снимается — иначе каждое следующее сообщение упиралось бы в тот же отказ;
 *   <li>{@value #CHAT_NOT_FOUND} — 400 «chat not found»: чата для бота больше нет — так же, с отвязкой;
 *   <li>{@value #REJECTED} — прочие 400: Telegram не принял само сообщение, повтор не поможет;
 *   <li>{@value #RATE_LIMITED} — 429: повтор не раньше {@code retry_after};
 *   <li>{@value #AUTH_FAILED} — 401 и 404: Telegram не знает токен (настройка сервера) — повтор, пока её исправляют;
 *   <li>{@value #SERVER_ERROR} — 5xx: повтор;
 *   <li>{@value #UNREACHABLE} — нет ответа: сеть, таймаут — повтор;
 *   <li>{@value #FAILED} — прочий ответ (неожиданный код, ответ без результата): повтор.
 * </ul>
 */
final class TelegramChannel implements NotificationChannel {

    static final String BLOCKED = "telegram_blocked";
    static final String CHAT_NOT_FOUND = "telegram_chat_not_found";
    static final String REJECTED = "telegram_rejected";
    static final String RATE_LIMITED = "telegram_rate_limited";
    static final String AUTH_FAILED = "telegram_auth_failed";
    static final String SERVER_ERROR = "telegram_server_error";
    static final String UNREACHABLE = "telegram_unreachable";
    static final String FAILED = "telegram_error";

    /** Предел длины текста сообщения Telegram (UTF-16). */
    static final int MAX_TEXT_LENGTH = 4096;

    private static final String ELLIPSIS = "…";

    private final TelegramApi api;

    TelegramChannel(TelegramApi api) {
        this.api = api;
    }

    @Override
    public Channel channel() {
        return Channel.TELEGRAM;
    }

    @Override
    public boolean configured() {
        return api.configured();
    }

    @Override
    public SendResult send(ResolvedRecipient recipient, RenderedText text) {
        var chatId = recipient.chatId();
        if (!api.configured() || chatId == null) {
            throw new InternalException("telegram is sent only by a configured channel to a linked chat");
        }
        try {
            api.sendMessage(chatId, fit(text.body()));
            return new SendResult.Sent();
        } catch (TelegramApiException e) {
            return result(e);
        }
    }

    /**
     * Текст в пределе Telegram: длиннее — обрезается с многоточием, не разрывая суррогатную пару. Шаблоны короче
     * предела в разы; это защита, а не способ доставки длинных текстов.
     */
    static String fit(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        var end = MAX_TEXT_LENGTH - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    private static SendResult result(TelegramApiException error) {
        if (!error.answered()) {
            return new SendResult.Retry(null, UNREACHABLE);
        }
        var status = error.status();
        return switch (status) {
            case 403 -> new SendResult.Fail(BLOCKED, true);
            case 400 ->
                error.chatNotFound() ? new SendResult.Fail(CHAT_NOT_FOUND, true) : new SendResult.Fail(REJECTED, false);
            case 429 -> new SendResult.Retry(error.retryAfter(), RATE_LIMITED);
            case 401, 404 -> new SendResult.Retry(null, AUTH_FAILED);
            default -> new SendResult.Retry(null, status >= 500 ? SERVER_ERROR : FAILED);
        };
    }
}
