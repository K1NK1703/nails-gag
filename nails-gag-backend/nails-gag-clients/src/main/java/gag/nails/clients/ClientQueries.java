package gag.nails.clients;

import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Чтение клиентов: мастер видит всех, клиент — только себя. */
public interface ClientQueries {

    /** @throws ClientNotFoundException если клиента нет */
    ClientView get(UUID clientId, Actor actor);

    /** Для других модулей: без проверки прав, они проверяют их сами; наружу по HTTP как есть не отдавать. */
    Optional<ClientView> byId(UUID clientId);

    /**
     * Для других модулей: без проверки прав, наружу по HTTP как есть не отдавать. Телефон в любом
     * написании; неразборчивый телефон — пусто, а не ошибка.
     */
    Optional<ClientView> byPhone(String phone);

    /** Только мастер. Пустой запрос — все клиенты по алфавиту. */
    List<ClientView> search(String query, Actor actor);

    /**
     * Только мастер.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    Optional<String> masterNote(UUID clientId, Actor actor);

    /** Есть ли у клиента согласия всех перечисленных видов на действующие версии документов. Без проверки прав. */
    boolean hasCurrentConsents(UUID clientId, Set<ConsentKind> kinds);

    /** Журнал согласий клиента, новые первыми. Мастер или сам клиент. */
    List<ConsentView> consents(UUID clientId, Actor actor);

    /** Журнал изменений признака предоплаты, новые первыми. Только мастер. */
    List<PrepaymentFlagChangeView> prepaymentHistory(UUID clientId, Actor actor);
}
