package gag.nails.identity.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.AccountView;
import gag.nails.identity.Accounts;
import gag.nails.identity.error.AccountNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Свой аккаунт (спецификация 4.6). Удаление: сначала правило последнего администратора (без этого
 * аккаунта), затем обезличивание карточки в {@code clients} и удаление способов входа, сессий, ролей,
 * переопределений и самого аккаунта; строки журнала входов теряют ссылку ({@code ON DELETE SET NULL}).
 *
 * <p>Известное ограничение: удаление берёт блокировку роли {@code DEVELOPER}, а затем строку карточки;
 * блокировка той же карточки мастером идёт в обратном порядке (карточка → роль). Совпав по времени, они
 * могут взаимно заблокироваться — PostgreSQL прервёт одну из транзакций, её достаточно повторить. Принято
 * из-за редкости: оба действия ручные.
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
            AccessGuard guard,
            ClientService clientService,
            ClientQueries clientQueries) {
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.sessions = sessions;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.assembly = assembly;
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
        guard.requireAdminRemains(accountId);
        clientQueries.byAccountId(accountId).map(ClientView::id).ifPresent(clientService::erase);
        sessions.deleteAllByAccountId(accountId);
        loginMethods.deleteAllByAccountId(accountId);
        accountRoles.deleteAllByIdAccountId(accountId);
        overrides.deleteAllByIdAccountId(accountId);
        accounts.delete(account);
    }

    private AccountEntity require(Actor actor) {
        UUID accountId = actor.accountId();
        if (accountId == null) {
            throw new AccessDeniedException();
        }
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
