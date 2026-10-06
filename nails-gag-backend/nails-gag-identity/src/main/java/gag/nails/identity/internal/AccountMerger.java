package gag.nails.identity.internal;

import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Слияние двух аккаунтов одного человека: способы входа, роли (без дублей) и переопределения (у
 * оставшегося приоритет) переходят оставшемуся, сессии удаляемого закрываются, сам он удаляется.
 * Строки журнала входов удаляемого аккаунта теряют ссылку ({@code ON DELETE SET NULL}), журнал прав
 * хранит идентификаторы как есть. Правило последнего администратора проверяет вызывающий.
 */
@Component
class AccountMerger {

    private final AccountRepository accounts;
    private final LoginMethodRepository loginMethods;
    private final AccountRoleRepository accountRoles;
    private final AccessOverrideRepository overrides;
    private final SessionRepository sessions;

    AccountMerger(
            AccountRepository accounts,
            LoginMethodRepository loginMethods,
            AccountRoleRepository accountRoles,
            AccessOverrideRepository overrides,
            SessionRepository sessions) {
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.sessions = sessions;
    }

    void merge(UUID removedAccountId, UUID survivorAccountId) {
        loginMethods
                .findAllByAccountIdOrderByLinkedAtAscIdAsc(removedAccountId)
                .forEach(method -> method.reassignTo(survivorAccountId));

        var survivorRoles = accountRoles.findAllByIdAccountId(survivorAccountId).stream()
                .map(AccountRoleEntity::roleId)
                .collect(Collectors.toSet());
        for (var assignment : accountRoles.findAllByIdAccountId(removedAccountId)) {
            if (!survivorRoles.contains(assignment.roleId())) {
                accountRoles.save(new AccountRoleEntity(survivorAccountId, assignment.roleId()));
            }
            accountRoles.delete(assignment);
        }

        var survivorOverrides = overrides.findAllByIdAccountId(survivorAccountId).stream()
                .map(AccessOverrideEntity::section)
                .collect(Collectors.toSet());
        for (var override : overrides.findAllByIdAccountId(removedAccountId)) {
            if (!survivorOverrides.contains(override.section())) {
                overrides.save(new AccessOverrideEntity(survivorAccountId, override.section(), override.level()));
            }
            overrides.delete(override);
        }

        sessions.deleteAllByAccountId(removedAccountId);
        accounts.findById(removedAccountId).ifPresent(accounts::delete);
    }
}
