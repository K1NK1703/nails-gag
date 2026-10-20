package gag.nails.clients;

import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentView;
import gag.nails.clients.model.ConsentWithdrawalView;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
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
     * Карточка <b>без аккаунта</b> с такой почтой без учёта регистра, если она ровно одна; две и больше —
     * пусто, между ними не угадываем. Ищет так же, как {@link ClientService#register} выбирает карточку по
     * почте: модуль входа сопоставляет и привязывает одну и ту же карточку. Для других модулей: без
     * проверки прав, наружу по HTTP как есть не отдавать. У обезличенной карточки почты нет, поэтому она
     * не находится.
     */
    Optional<ClientView> byEmail(String email);

    /**
     * Карточка, привязанная к аккаунту входа. Для других модулей: без проверки прав, наружу по HTTP
     * как есть не отдавать. У обезличенной карточки аккаунта нет, поэтому она не находится.
     */
    Optional<ClientView> byAccountId(UUID accountId);

    /**
     * Нужен уровень чтения раздела «Клиенты». Ищет по имени, фамилии, телефону и номеру для связи;
     * пустой запрос — все клиенты по алфавиту. Обезличенные карточки не возвращаются.
     */
    List<ClientView> search(String query, Actor actor);

    /** То же, что {@link #search(String, Actor)}, постранично; порядок тот же, {@code total} — все совпавшие. */
    Paged<ClientView> search(String query, Paging paging, Actor actor);

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
     * Есть ли у клиента неотозванные согласия всех перечисленных видов на действующие версии документов; для вида,
     * которому редакция не важна ({@link ConsentKind#requiresCurrentVersion()}), — на любую. Без
     * проверки прав. Пустой набор — {@code true}; для несуществующего клиента — {@code false}.
     */
    boolean hasCurrentConsents(UUID clientId, Set<ConsentKind> kinds);

    /**
     * Журнал согласий клиента, новые первыми. Отозванные согласия остаются в нём, отзывы — в
     * {@link #consentWithdrawals}. Чтение раздела «Клиенты» или сам клиент.
     */
    List<ConsentView> consents(UUID clientId, Actor actor);

    /** Отзывы согласий клиента, новые первыми. Чтение раздела «Клиенты» или сам клиент. */
    List<ConsentWithdrawalView> consentWithdrawals(UUID clientId, Actor actor);

    /** Журнал изменений признака предоплаты, новые первыми. Нужен уровень чтения раздела «Клиенты». */
    List<PrepaymentFlagChangeView> prepaymentHistory(UUID clientId, Actor actor);

    /**
     * Последнее изменение признака предоплаты. Для других модулей: без проверки прав, наружу по HTTP как
     * есть не отдавать.
     */
    Optional<PrepaymentFlagChangeView> lastPrepaymentChange(UUID clientId);
}
