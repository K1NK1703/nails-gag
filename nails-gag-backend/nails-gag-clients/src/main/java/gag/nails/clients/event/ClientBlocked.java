package gag.nails.clients.event;

import gag.nails.clients.ClientService;
import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Клиент заблокирован; публикуется при каждом вызове {@link ClientService#block}, в том числе при
 * смене причины у уже заблокированного, а также при объединении ({@link ClientService#merge},
 * {@link ClientService#mergeOwn}), если оставшаяся карточка стала заблокированной из-за него — тогда
 * вслед за {@link ClientMerged}. Публикуется внутри транзакции операции; подписчики — обычный
 * {@code @EventListener} в той же транзакции (модуль входа закрывает сессии аккаунта и проверяет
 * правило последнего администратора: его исключение откатывает операцию).
 *
 * @param accountId аккаунт входа клиента; пусто у карточки без аккаунта
 */
public record ClientBlocked(UUID clientId, @Nullable UUID accountId, Instant occurredAt) implements DomainEvent {}
