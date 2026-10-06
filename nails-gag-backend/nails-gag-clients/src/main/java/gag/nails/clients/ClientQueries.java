package gag.nails.clients;

import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Чтение клиентов. Сотрудник с уровнем чтения раздела «Клиенты» видит всех, клиент — только себя
 * ({@link AccessDeniedException}).
 */
public interface ClientQueries {

    /**
     * Чтение раздела «Клиенты» или сам клиент.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView get(UUID clientId, Actor actor);

    /** Для других модулей: без проверки прав, они проверяют их сами; наружу по HTTP как есть не отдавать. */
    Optional<ClientView> byId(UUID clientId);

    /**
     * Для других модулей: без проверки прав, наружу по HTTP как есть не отдавать. Телефон в любом
     * написании; неразборчивый телефон — пусто, а не ошибка.
     */
    Optional<ClientView> byPhone(String phone);

    /**
     * Нужен уровень чтения раздела «Клиенты». Ищет по имени, фамилии, телефону и номеру для связи;
     * пустой запрос — все клиенты по алфавиту. Обезличенные карточки не возвращаются.
     */
    List<ClientView> search(String query, Actor actor);

    /**
     * Нужен уровень чтения раздела «Клиенты».
     *
     * @throws ClientNotFoundException если клиента нет
     */
    Optional<String> masterNote(UUID clientId, Actor actor);

    /**
     * Причина блокировки; пусто, если клиент не заблокирован или причина не указана. Клиенту не видна:
     * нужен уровень чтения раздела «Клиенты».
     *
     * @throws ClientNotFoundException если клиента нет
     */
    Optional<String> blockReason(UUID clientId, Actor actor);

    /**
     * Есть ли у клиента согласия всех перечисленных видов на действующие версии документов. Без
     * проверки прав. Пустой набор — {@code true}; для несуществующего клиента — {@code false}.
     */
    boolean hasCurrentConsents(UUID clientId, Set<ConsentKind> kinds);

    /** Журнал согласий клиента, новые первыми. Чтение раздела «Клиенты» или сам клиент. */
    List<ConsentView> consents(UUID clientId, Actor actor);

    /** Журнал изменений признака предоплаты, новые первыми. Нужен уровень чтения раздела «Клиенты». */
    List<PrepaymentFlagChangeView> prepaymentHistory(UUID clientId, Actor actor);

    /**
     * Последнее изменение признака предоплаты. Для других модулей: без проверки прав, наружу по HTTP как
     * есть не отдавать.
     */
    Optional<PrepaymentFlagChangeView> lastPrepaymentChange(UUID clientId);
}
