package gag.nails.identity.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.common.validation.CommandValidator;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.AccessChangeKind;
import gag.nails.identity.AccessChangeView;
import gag.nails.identity.AccountAccessView;
import gag.nails.identity.CreateRole;
import gag.nails.identity.LoginEventView;
import gag.nails.identity.RoleView;
import gag.nails.identity.UpdateRole;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.RoleBuiltInException;
import gag.nails.identity.error.RoleCodeTakenException;
import gag.nails.identity.error.RoleInUseException;
import gag.nails.identity.error.RoleNotFoundException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Раздел «Доступ». Каждая результативная операция оставляет строку в журнале прав; повторное назначение,
 * снятие неназначенной роли и переопределение тем же уровнем — не операции и в журнал не попадают.
 * Правило последнего администратора ({@link AccessGuard}) проверяется после изменения роли, её
 * удаления, снятия роли и переопределения.
 */
@Service
@Transactional
class DefaultAccessAdministration implements AccessAdministration {

    /** Уникальный индекс кода роли из миграции {@code 0001-init}. */
    private static final String ROLE_CODE_KEY = "role_code_key";

    private static final int MAX_JOURNAL_LIMIT = 500;

    private final RoleRepository roles;
    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final AccessOverrideRepository overrides;
    private final LoginMethodRepository loginMethods;
    private final AccessResolver resolver;
    private final AccessGuard guard;
    private final AccessJournal journal;
    private final LoginEvents loginEvents;
    private final ClientQueries clients;
    private final CommandValidator validator;

    DefaultAccessAdministration(
            RoleRepository roles,
            AccountRepository accounts,
            AccountRoleRepository accountRoles,
            AccessOverrideRepository overrides,
            LoginMethodRepository loginMethods,
            AccessResolver resolver,
            AccessGuard guard,
            AccessJournal journal,
            LoginEvents loginEvents,
            ClientQueries clients,
            CommandValidator validator) {
        this.roles = roles;
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.loginMethods = loginMethods;
        this.resolver = resolver;
        this.guard = guard;
        this.journal = journal;
        this.loginEvents = loginEvents;
        this.clients = clients;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleView> roles(Actor actor) {
        actor.requireRead(Section.ACCESS);
        return roles.findAllByOrderByCodeAsc().stream().map(RoleEntity::toView).toList();
    }

    @Override
    public RoleView createRole(CreateRole command, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        validator.requireValid(command);
        if (roles.findByCode(command.code()).isPresent()) {
            throw new RoleCodeTakenException(command.code());
        }
        var role = insert(new RoleEntity(command.code(), command.title(), command.grants()));
        journal.record(actor.accountId(), null, role.id(), AccessChangeKind.ROLE_CREATED, null, null, null);
        return role.toView();
    }

    @Override
    public RoleView updateRole(UUID roleId, UpdateRole command, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        validator.requireValid(command);
        var role = requireRole(roleId);
        if (AccessGuard.DEVELOPER.equals(role.code())
                && command.grants().getOrDefault(Section.ACCESS, AccessLevel.NONE) != AccessLevel.WRITE) {
            throw new RoleBuiltInException();
        }
        var before = role.grants();
        var renamed = !role.title().equals(command.title());
        role.rename(command.title());
        role.replaceGrants(command.grants());
        var after = role.grants();
        var changed = false;
        for (var section : Section.values()) {
            var oldLevel = before.getOrDefault(section, AccessLevel.NONE);
            var newLevel = after.getOrDefault(section, AccessLevel.NONE);
            if (oldLevel != newLevel) {
                changed = true;
                journal.record(
                        actor.accountId(), null, roleId, AccessChangeKind.ROLE_UPDATED, section, oldLevel, newLevel);
            }
        }
        if (renamed && !changed) {
            journal.record(actor.accountId(), null, roleId, AccessChangeKind.ROLE_UPDATED, null, null, null);
        }
        guard.requireAdminRemains();
        return role.toView();
    }

    @Override
    public void deleteRole(UUID roleId, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        var role = requireRole(roleId);
        if (role.builtIn()) {
            throw new RoleBuiltInException();
        }
        if (accountRoles.existsByIdRoleId(roleId)) {
            throw new RoleInUseException(roleId);
        }
        roles.delete(role);
        journal.record(actor.accountId(), null, roleId, AccessChangeKind.ROLE_DELETED, null, null, null);
        guard.requireAdminRemains();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountAccessView> staff(Actor actor) {
        actor.requireRead(Section.ACCESS);
        return accounts.findStaff().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountAccessView account(UUID accountId, Actor actor) {
        actor.requireRead(Section.ACCESS);
        return view(requireAccount(accountId));
    }

    @Override
    public AccountAccessView assignRole(UUID accountId, UUID roleId, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        var account = requireAccount(accountId);
        var role = requireRole(roleId);
        var id = new AccountRoleId(accountId, roleId);
        if (!accountRoles.existsById(id)) {
            accountRoles.save(new AccountRoleEntity(accountId, roleId));
            journal.record(actor.accountId(), accountId, role.id(), AccessChangeKind.ROLE_ASSIGNED, null, null, null);
        }
        return view(account);
    }

    @Override
    public AccountAccessView revokeRole(UUID accountId, UUID roleId, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        var account = requireAccount(accountId);
        var role = requireRole(roleId);
        var assigned = accountRoles.findById(new AccountRoleId(accountId, roleId));
        if (assigned.isPresent()) {
            accountRoles.delete(assigned.get());
            journal.record(actor.accountId(), accountId, role.id(), AccessChangeKind.ROLE_REVOKED, null, null, null);
            guard.requireAdminRemains();
        }
        return view(account);
    }

    @Override
    public AccountAccessView setOverride(UUID accountId, Section section, @Nullable AccessLevel level, Actor actor) {
        actor.requireWrite(Section.ACCESS);
        var account = requireAccount(accountId);
        var existing =
                overrides.findById(new AccessOverrideId(accountId, section)).orElse(null);
        var oldLevel = existing == null ? null : existing.level();
        if (level == oldLevel) {
            return view(account);
        }
        if (level == null) {
            overrides.delete(existing);
            journal.record(
                    actor.accountId(), accountId, null, AccessChangeKind.OVERRIDE_CLEARED, section, oldLevel, null);
        } else {
            if (existing == null) {
                overrides.save(new AccessOverrideEntity(accountId, section, level));
            } else {
                existing.setLevel(level);
            }
            journal.record(actor.accountId(), accountId, null, AccessChangeKind.OVERRIDE_SET, section, oldLevel, level);
        }
        guard.requireAdminRemains();
        return view(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccessChangeView> accessJournal(int limit, Actor actor) {
        actor.requireRead(Section.ACCESS);
        requireLimit(limit);
        return journal.latest(limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LoginEventView> loginJournal(int limit, Actor actor) {
        actor.requireRead(Section.ACCESS);
        requireLimit(limit);
        return loginEvents.latest(limit);
    }

    private AccountAccessView view(AccountEntity account) {
        var resolved = resolver.resolve(account.id());
        var card = clients.byAccountId(account.id());
        var displayName = loginMethods
                .findFirstByAccountIdOrderByLastUsedAtDescIdDesc(account.id())
                .map(LoginMethodEntity::displayName)
                .orElse(null);
        return new AccountAccessView(
                account.id(),
                card.map(ClientView::id).orElse(null),
                displayName,
                resolved.roles().stream().map(RoleEntity::toView).toList(),
                resolved.overrides(),
                resolved.effective(),
                card.map(ClientView::blocked).orElse(false));
    }

    /**
     * Два одновременных создания роли с одним кодом: проверка выше у обоих прошла, уникальный индекс
     * пропустит одного. Flush здесь, чтобы нарушение стало исключением этого модуля; после него
     * транзакция PostgreSQL прервана, исключению дают всплыть.
     */
    private RoleEntity insert(RoleEntity role) {
        try {
            return roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException e) {
            var name = ConstraintViolations.constraintName(e);
            if (name.filter(ROLE_CODE_KEY::equals).isPresent()) {
                throw new RoleCodeTakenException(role.code());
            }
            throw new InternalException("role write violated constraint " + name.orElse("<unknown>"), e);
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_JOURNAL_LIMIT) {
            throw new ValidationException("limit", "must be between 1 and " + MAX_JOURNAL_LIMIT);
        }
    }

    private RoleEntity requireRole(UUID roleId) {
        return roles.findById(roleId).orElseThrow(() -> new RoleNotFoundException(roleId));
    }

    private AccountEntity requireAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
