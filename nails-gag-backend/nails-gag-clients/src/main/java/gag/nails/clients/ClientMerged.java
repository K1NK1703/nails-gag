package gag.nails.clients;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Два клиента объединены: всё, что ссылалось на {@code removedClientId}, должно ссылаться на
 * {@code survivorClientId}. Публикуется внутри транзакции объединения; слушатели выполняются в ней же,
 * синхронно, и к этому моменту удалённая карточка уже удалена — слушателям нужны только два
 * идентификатора. Подписчики — обычный {@code @EventListener} в той же транзакции, не
 * {@code @ApplicationModuleListener} (он срабатывает после фиксации и асинхронно).
 */
public record ClientMerged(UUID removedClientId, UUID survivorClientId, Instant occurredAt) implements DomainEvent {}
