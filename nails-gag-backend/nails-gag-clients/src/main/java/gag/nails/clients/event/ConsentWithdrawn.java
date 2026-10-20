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
 */
public record ConsentWithdrawn(UUID clientId, ConsentKind kind, Instant occurredAt) implements DomainEvent {}
