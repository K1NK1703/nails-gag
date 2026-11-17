package gag.nails.notifications.internal.dispatch;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Карточки клиентов, прочитанные один раз на время работы: на прогон постановки доставок (сообщения одной карточки
 * не перечитывают её) или на одну отправку (карточка записи нужна и получателю, и тексту). Не потокобезопасен.
 */
final class Cards {

    private final ClientQueries clients;
    private final Map<UUID, Optional<ClientView>> byId = new HashMap<>();
    private final Map<UUID, Optional<ClientView>> byAccountId = new HashMap<>();

    Cards(ClientQueries clients) {
        this.clients = clients;
    }

    Optional<ClientView> byId(UUID clientId) {
        return byId.computeIfAbsent(clientId, clients::byId);
    }

    /** Карточка аккаунта сотрудника: почта сотрудника. */
    Optional<ClientView> byAccountId(UUID accountId) {
        return byAccountId.computeIfAbsent(accountId, clients::byAccountId);
    }
}
