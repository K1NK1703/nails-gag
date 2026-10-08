package gag.nails.identity.internal.account;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.Accounts;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.internal.access.AccessGuard;
import gag.nails.identity.internal.access.AccessOverrideRepository;
import gag.nails.identity.internal.access.AccessResolver;
import gag.nails.identity.internal.access.AccountRoleRepository;
import gag.nails.identity.internal.session.ActorAssembly;
import gag.nails.identity.internal.session.SessionRepository;
import gag.nails.identity.model.AccountView;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Свой аккаунт (спецификация 4.6). Удаление в одной транзакции: обезличивание карточки в {@code
 * clients}, затем удаление способов входа, сессий, ролей, переопределений и самого аккаунта (строки
 * журнала входов теряют ссылку, {@code ON DELETE SET NULL}); правило последнего администратора —
 * последним, уже без уходящего. Проверка нужна только уходящему администратору: у остального клиента
 * терять системе нечего, и блокировка роли {@code DEVELOPER} не берётся — пока в системе нет ни одного
 * администратора, клиент всё равно вправе удалить аккаунт. Порядок блокировок строк — карточка →
 * аккаунт → роль, как у блокировки клиента мастером, поэтому взаимной блокировки с ней нет.
 *
 * <p>Известное ограничение: вход при фиксации обновляет строки аккаунта ({@code last_login_at}) и, если
 * записывает подтверждённый номер, карточки — в порядке аккаунт → клиент. Совпав по времени с удалением
 * того же аккаунта (клиент входит с другого устройства и одновременно удаляет аккаунт), они могут
 * взаимно заблокироваться; PostgreSQL прервёт одну из транзакций, её достаточно повторить.
 */
@Service
@Transactional
class DefaultAccounts implements Accounts {

    private final AccountRepository accounts;
    private final LoginMethodRepository loginMethods;
    private final SessionRepository sessions;
    private final AccountRoleRepository accountRoles;
    private final AccessOverrideRepository overrides;
    private final ActorAssembly assembly;
    private final AccessResolver resolver;
    private final AccessGuard guard;
    private final ClientService clientService;
    private final ClientQueries clientQueries;

    DefaultAccounts(
            AccountRepository accounts,
            LoginMethodRepository loginMethods,
            SessionRepository sessions,
            AccountRoleRepository accountRoles,
            AccessOverrideRepository overrides,
            ActorAssembly assembly,
            AccessResolver resolver,
            AccessGuard guard,
            ClientService clientService,
            ClientQueries clientQueries) {
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.sessions = sessions;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.assembly = assembly;
        this.resolver = resolver;
        this.guard = guard;
        this.clientService = clientService;
        this.clientQueries = clientQueries;
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView me(Actor actor) {
        var account = require(actor);
        var lastUsed = loginMethods
                .findFirstByAccountIdOrderByLastUsedAtDescIdDesc(account.id())
                .orElse(null);
        return assembly.assemble(account, lastUsed, assembly.card(account)).account();
    }

    @Override
    public void delete(Actor actor) {
        var account = require(actor);
        var accountId = account.id();
        var leavingAdmin = resolver.effective(accountId).get(Section.ACCESS) == AccessLevel.WRITE;
        clientQueries.byAccountId(accountId).map(ClientView::id).ifPresent(clientService::erase);
        sessions.deleteAllByAccountId(accountId);
        loginMethods.deleteAllByAccountId(accountId);
        accountRoles.deleteAllByIdAccountId(accountId);
        overrides.deleteAllByIdAccountId(accountId);
        accounts.delete(account);
        if (leavingAdmin) {
            guard.requireAdminRemains();
        }
    }

    private AccountEntity require(Actor actor) {
        UUID accountId = actor.accountId();
        if (accountId == null) {
            throw new AccessDeniedException();
        }
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
