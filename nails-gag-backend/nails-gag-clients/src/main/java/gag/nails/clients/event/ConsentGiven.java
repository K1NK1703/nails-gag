package gag.nails.clients.event;

import gag.nails.clients.ClientService;
import gag.nails.clients.model.ConsentKind;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент дал согласие ({@link ClientService#recordConsent}). Публикуется при каждой записи, в том числе
 * повторной, когда такое согласие уже действует, — подписчики должны переносить повтор. Публикуется внутри
 * транзакции операции; подписчики — обычный {@code @EventListener} в той же транзакции (модуль портфолио
 * отмечает разрешение публиковать фото клиента).
 */
public record ConsentGiven(UUID clientId, ConsentKind kind, Instant occurredAt) implements DomainEvent {}
