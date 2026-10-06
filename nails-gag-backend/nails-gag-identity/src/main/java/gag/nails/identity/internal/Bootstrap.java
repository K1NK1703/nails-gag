package gag.nails.identity.internal;

import gag.nails.common.error.InternalException;
import gag.nails.identity.AccessChangeKind;
import gag.nails.identity.Provider;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Первый разработчик (спецификация 4.4): способ входа из {@code nails-gag.identity.bootstrap-developers}
 * получает роль {@code DEVELOPER} при каждом входе и при завершении регистрации, если её ещё нет; в
 * журнал прав пишется {@link AccessChangeKind#BOOTSTRAP} без актора. Повторный вход роль не дублирует и
 * строки в журнале не добавляет.
 */
@Component
class Bootstrap {

    private final Set<String> developers;
    private final RoleRepository roles;
    private final AccountRoleRepository accountRoles;
    private final AccessJournal journal;

    Bootstrap(
            IdentityProperties properties,
            RoleRepository roles,
            AccountRoleRepository accountRoles,
            AccessJournal journal) {
        this.developers = Set.copyOf(properties.bootstrapDevelopers());
        this.roles = roles;
        this.accountRoles = accountRoles;
        this.journal = journal;
    }

    void apply(UUID accountId, Provider provider, String subject) {
        if (!developers.contains(provider.name() + ":" + subject)) {
            return;
        }
        var developer = roles.findByCode(AccessGuard.DEVELOPER)
                .orElseThrow(() -> new InternalException("built-in role DEVELOPER is missing"));
        if (accountRoles.existsById(new AccountRoleId(accountId, developer.id()))) {
            return;
        }
        accountRoles.save(new AccountRoleEntity(accountId, developer.id()));
        journal.record(null, accountId, developer.id(), AccessChangeKind.BOOTSTRAP, null, null, null);
    }
}
