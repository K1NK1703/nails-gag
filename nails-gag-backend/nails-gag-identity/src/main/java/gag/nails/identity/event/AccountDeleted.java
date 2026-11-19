package gag.nails.identity.event;

import gag.nails.common.event.DomainEvent;
import gag.nails.identity.Accounts;
import java.time.Instant;
import java.util.UUID;

/**
 * Аккаунт удалён. Публикуется при любом удалении аккаунта, в той же транзакции: владельцем ({@link
 * Accounts#delete}) и при слиянии аккаунтов — объединении карточек, у обеих из которых был аккаунт, и
 * привязке способа входа другого аккаунта без карточки (его способы входа и права переходят оставшемуся).
 * Публикуется сразу после удаления: способы входа, сессии, роли, переопределения и сам аккаунт удалены в этой
 * транзакции (возможно, ещё не сброшены в базу); у владельца карточка уже обезличена и правило последнего
 * администратора пройдено. Подписчики — обычный {@code @EventListener} в той же транзакции: их отказ
 * откатывает удаление.
 */
public record AccountDeleted(UUID accountId, Instant occurredAt) implements DomainEvent {}
