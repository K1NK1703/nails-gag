package gag.nails.identity.internal.account;

import gag.nails.identity.event.AccountDeleted;
import gag.nails.identity.internal.access.AccessJournal;
import gag.nails.identity.internal.access.AccessOverrideEntity;
import gag.nails.identity.internal.access.AccessOverrideRepository;
import gag.nails.identity.internal.access.AccountRoleEntity;
import gag.nails.identity.internal.access.AccountRoleRepository;
import gag.nails.identity.internal.session.SessionRepository;
import gag.nails.identity.model.AccessChangeKind;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Слияние двух аккаунтов одного человека: способы входа, роли (без дублей) и переопределения (у
 * оставшегося приоритет) переходят оставшемуся, сессии удаляемого закрываются, сам он удаляется.
 * Перенесённые роли и переопределения попадают в журнал прав ({@code ROLE_ASSIGNED}, {@code
 * OVERRIDE_SET}) без актора: права оставшегося изменились не по решению администратора. Строки журнала
 * входов удаляемого аккаунта теряют ссылку ({@code ON DELETE SET NULL}), журнал прав хранит
 * идентификаторы как есть. Сразу после удаления аккаунта в той же транзакции публикуется {@link
 * AccountDeleted} — при любом слиянии: объединении карточек с двумя аккаунтами ({@code ClientMergedListener})
 * и привязке способа входа другого аккаунта без карточки ({@code LoginWriter}). Правило последнего
 * администратора проверяет вызывающий.
 *
 * <p>Первым делом строка удаляемого аккаунта блокируется {@code FOR UPDATE}
 * ({@link AccountRepository#lockForDeletion}), как при удалении владельцем: модули, которые держат её
 * {@code FOR KEY SHARE} на время записи своих данных аккаунта, успевают зафиксироваться, и слушатели
 * {@link AccountDeleted} их стирают. Аккаунта уже нет — слияния нет.
 */
@Component
public class AccountMerger {

    private final AccountRepository accounts;
    private final LoginMethodRepository loginMethods;
    private final AccountRoleRepository accountRoles;
    private final AccessOverrideRepository overrides;
    private final SessionRepository sessions;
    private final AccessJournal journal;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AccountMerger(
            AccountRepository accounts,
            LoginMethodRepository loginMethods,
            AccountRoleRepository accountRoles,
            AccessOverrideRepository overrides,
            SessionRepository sessions,
            AccessJournal journal,
            ApplicationEventPublisher events,
            Clock clock) {
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.sessions = sessions;
        this.journal = journal;
        this.events = events;
        this.clock = clock;
    }

    public void merge(UUID removedAccountId, UUID survivorAccountId) {
        if (accounts.lockForDeletion(removedAccountId).isEmpty()) {
            return;
        }
        loginMethods
                .findAllByAccountIdOrderByLinkedAtAscIdAsc(removedAccountId)
                .forEach(method -> method.reassignTo(survivorAccountId));

        var survivorRoles = accountRoles.findAllByIdAccountId(survivorAccountId).stream()
                .map(AccountRoleEntity::roleId)
                .collect(Collectors.toSet());
        for (var assignment : accountRoles.findAllByIdAccountId(removedAccountId)) {
            if (!survivorRoles.contains(assignment.roleId())) {
                accountRoles.save(new AccountRoleEntity(survivorAccountId, assignment.roleId()));
                journal.record(
                        null, survivorAccountId, assignment.roleId(), AccessChangeKind.ROLE_ASSIGNED, null, null, null);
            }
            accountRoles.delete(assignment);
        }

        var survivorOverrides = overrides.findAllByIdAccountId(survivorAccountId).stream()
                .map(AccessOverrideEntity::section)
                .collect(Collectors.toSet());
        for (var override : overrides.findAllByIdAccountId(removedAccountId)) {
            if (!survivorOverrides.contains(override.section())) {
                overrides.save(new AccessOverrideEntity(survivorAccountId, override.section(), override.level()));
                journal.record(
                        null,
                        survivorAccountId,
                        null,
                        AccessChangeKind.OVERRIDE_SET,
                        override.section(),
                        null,
                        override.level());
            }
            overrides.delete(override);
        }

        sessions.deleteAllByAccountId(removedAccountId);
        accounts.delete(accounts.getReferenceById(removedAccountId));
        events.publishEvent(new AccountDeleted(removedAccountId, clock.instant()));
    }
}
