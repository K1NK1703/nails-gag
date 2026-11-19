package gag.nails.notifications.internal.dispatch;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Исход одной попытки отправки ({@link NotificationChannel#send}). Коды ошибок — строчные латинские буквы, цифры и
 * подчёркивание, до 32 символов (колонка {@code delivery.error_code}): {@code telegram_blocked},
 * {@code telegram_rejected}, {@code email_rejected} и коды временных сбоев канала.
 */
public sealed interface SendResult {

    /** Доставлено. */
    record Sent() implements SendResult {}

    /**
     * Временный сбой: повторить по расписанию повторов, но не раньше {@code notBefore} от момента ответа (Telegram 429
     * {@code retry_after}).
     *
     * @param notBefore сколько подождать до следующей попытки; пусто — по расписанию повторов
     * @param errorCode код сбоя — в журнал доставок как код последней ошибки
     */
    record Retry(@Nullable Duration notBefore, String errorCode) implements SendResult {

        public Retry {
            ErrorCodes.require(errorCode);
        }
    }

    /**
     * Постоянный отказ: доставка неудачна сразу, без повторов.
     *
     * @param errorCode код отказа — в журнал доставок
     * @param unlinkTelegram получатель заблокировал бота: привязку этого чата к аккаунту снять
     */
    record Fail(String errorCode, boolean unlinkTelegram) implements SendResult {

        public Fail {
            ErrorCodes.require(errorCode);
        }
    }
}
