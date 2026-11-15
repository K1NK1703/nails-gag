package gag.nails.notifications.error;

import gag.nails.common.error.UserFacingException;

/** Telegram-бот не настроен на сервере: привязать чат нельзя (HTTP 409). */
public final class TelegramUnavailableException extends UserFacingException {

    public TelegramUnavailableException() {
        super("notifications.telegram_unavailable");
    }
}
