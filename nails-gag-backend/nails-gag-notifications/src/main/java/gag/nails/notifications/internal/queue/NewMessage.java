package gag.nails.notifications.internal.queue;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.model.MessageKind;
import java.time.Instant;
import java.util.UUID;

/**
 * Сообщение для постановки в очередь. Согласованность проверяется здесь, до базы (там те же ограничения): мастерские
 * виды ({@code STAFF_*}) — аккаунту, остальные — карточке; срок жизни позже срока отправки; ключ повтора помещается в
 * колонку.
 *
 * @param dueAt когда отправлять
 * @param expiresAt после этого момента сообщение не отправляется
 * @param dedupKey ключ повтора: второе сообщение с тем же ключом не ставится
 * @param occurredAt момент события, из-за которого сообщение ставится (у напоминания — момент расчёта): при равном
 *     {@code dueAt} сообщения отправляются в порядке событий
 */
public record NewMessage(
        MessageKind kind,
        Recipient recipient,
        UUID bookingId,
        Instant dueAt,
        Instant expiresAt,
        String dedupKey,
        Instant occurredAt) {

    /** Длина колонки {@code message.dedup_key}. */
    public static final int DEDUP_KEY_MAX_LENGTH = 255;

    private static final String STAFF_PREFIX = "STAFF_";

    public NewMessage {
        var staffKind = kind.name().startsWith(STAFF_PREFIX);
        if (staffKind != (recipient instanceof Recipient.Account)) {
            throw new InternalException("message kind " + kind + " does not match its recipient");
        }
        if (!expiresAt.isAfter(dueAt)) {
            throw new InternalException("message must expire after it is due");
        }
        if (dedupKey.isEmpty() || dedupKey.length() > DEDUP_KEY_MAX_LENGTH) {
            throw new InternalException("message dedup key length is out of range: " + dedupKey.length());
        }
    }

    /** Прежняя форма: момент события неизвестен — им считается срок отправки. */
    public NewMessage(
            MessageKind kind, Recipient recipient, UUID bookingId, Instant dueAt, Instant expiresAt, String dedupKey) {
        this(kind, recipient, bookingId, dueAt, expiresAt, dedupKey, dueAt);
    }
}
