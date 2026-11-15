package gag.nails.notifications.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала доставок: доставка сообщения по одному каналу. Получатель — ровно одно из {@code clientId} и
 * {@code accountId}. Адресов и текста нет.
 *
 * @param clientId карточка клиентки — у клиентских сообщений
 * @param accountId аккаунт сотрудника — у мастерских сообщений
 * @param attempts сколько попыток отправки уже было
 * @param errorCode код последней ошибки ({@code expired}, {@code telegram_blocked}…); пусто — ошибок не было
 * @param dueAt когда сообщение должно было уйти
 * @param sentAt когда отправлено; пусто — ещё не отправлено или не отправится
 */
public record DeliveryView(
        UUID deliveryId,
        UUID messageId,
        MessageKind kind,
        UUID bookingId,
        @Nullable UUID clientId,
        @Nullable UUID accountId,
        Channel channel,
        DeliveryStatus status,
        int attempts,
        @Nullable String errorCode,
        Instant dueAt,
        @Nullable Instant sentAt) {}
