package gag.nails.clients.event;

import gag.nails.clients.ClientService;
import gag.nails.clients.model.ConsentKind;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент или сотрудник отозвал согласие ({@link ClientService#withdrawConsent}); публикуется, только если было
 * что отзывать. Публикуется внутри транзакции отзыва; подписчики — обычный {@code @EventListener} в той же
 * транзакции (модуль портфолио сразу снимает работы клиента с публикации: отзыв и снятие фиксируются вместе).
 *
 * <p>При обезличивании и объединении карточек это событие не публикуется: подписчик обязан слушать и
 * {@link ClientErased}, и {@link ClientMerged}.
 */
public record ConsentWithdrawn(UUID clientId, ConsentKind kind, Instant occurredAt) implements DomainEvent {}
