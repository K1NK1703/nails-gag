package gag.nails.clients;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Два клиента объединены: всё, что ссылалось на {@code removedClientId}, должно ссылаться на
 * {@code survivorClientId}. Публикуется внутри транзакции объединения; слушатели выполняются в ней же,
 * синхронно, и к этому моменту удалённая карточка уже удалена — слушателям нужны только два
 * идентификатора. Подписчики — обычный {@code @EventListener} в той же транзакции, не
 * {@code @ApplicationModuleListener} (он срабатывает после фиксации и асинхронно).
 *
 * <p>Аккаунты — на момент объединения. Если аккаунт был у обеих карточек, модуль входа переносит
 * способы входа и права с {@code removedAccountId} на {@code survivorAccountId} и удаляет первый.
 * Если аккаунт был только у удаляемой карточки, он уже переведён на оставшуюся самим объединением.
 *
 * @param removedAccountId аккаунт удалённой карточки; пусто, если его не было
 * @param survivorAccountId аккаунт оставшейся карточки до объединения; пусто, если его не было
 */
public record ClientMerged(
        UUID removedClientId,
        UUID survivorClientId,
        @Nullable UUID removedAccountId,
        @Nullable UUID survivorAccountId,
        Instant occurredAt)
        implements DomainEvent {

    /** Объединение карточек без аккаунтов. */
    public ClientMerged(UUID removedClientId, UUID survivorClientId, Instant occurredAt) {
        this(removedClientId, survivorClientId, null, null, occurredAt);
    }
}
