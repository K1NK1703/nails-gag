package gag.nails.identity.event;

import gag.nails.common.event.DomainEvent;
import gag.nails.identity.Accounts;
import java.time.Instant;
import java.util.UUID;

/**
 * Аккаунт удалён владельцем ({@link Accounts#delete}). Публикуется в транзакции удаления после стирания:
 * карточка обезличена, способов входа, сессий, ролей, переопределений и самого аккаунта уже нет, правило
 * последнего администратора пройдено. Подписчики — обычный {@code @EventListener} в той же транзакции:
 * их отказ откатывает удаление.
 */
public record AccountDeleted(UUID accountId, Instant occurredAt) implements DomainEvent {}
