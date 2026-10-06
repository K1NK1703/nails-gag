package gag.nails.clients;

import gag.nails.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Клиент заблокирован сотрудником; публикуется при каждом вызове {@link ClientService#block}, в том
 * числе при смене причины у уже заблокированного. Публикуется внутри транзакции блокировки;
 * подписчики — обычный {@code @EventListener} в той же транзакции (модуль входа закрывает сессии
 * аккаунта и проверяет правило последнего администратора: его исключение откатывает блокировку).
 *
 * @param accountId аккаунт входа клиента; пусто у карточки без аккаунта
 */
public record ClientBlocked(UUID clientId, @Nullable UUID accountId, Instant occurredAt) implements DomainEvent {}
