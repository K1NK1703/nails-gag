package gag.nails.clients.event;

import gag.nails.clients.ClientService;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Карточка клиента обезличена при удалении аккаунта ({@link ClientService#erase}): личных данных и
 * аккаунта у неё больше нет, сама карточка и история остаются. Публикуется внутри транзакции
 * обезличивания, один раз на карточку; подписчики — обычный {@code @EventListener} в той же
 * транзакции (модуль записей отменяет будущие записи клиента и стирает его комментарии).
 */
public record ClientErased(UUID clientId, Instant occurredAt) implements DomainEvent {}
