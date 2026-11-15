package gag.nails.identity.internal.access;

import gag.nails.clients.ClientQueries;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.identity.StaffDirectory;
import gag.nails.identity.model.StaffRecipient;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Итоговые уровни — {@link AccessResolver}, тем же правилом, что у {@code Actor} сессии; уровень {@link
 * AccessLevel#NONE} у всех остальных аккаунтов, поэтому кандидаты — только аккаунты с ролями или
 * переопределениями. Карточка проверяется уже после фильтра по уровню, отдельным запросом на аккаунт:
 * сотрудников единицы. Заблокированная или обезличенная карточка исключает аккаунт, как и сессию.
 */
@Service
@Transactional(readOnly = true)
class DefaultStaffDirectory implements StaffDirectory {

    private final AccessResolver resolver;
    private final ClientQueries clients;

    DefaultStaffDirectory(AccessResolver resolver, ClientQueries clients) {
        this.resolver = resolver;
        this.clients = clients;
    }

    @Override
    public List<StaffRecipient> recipients(Section section, AccessLevel level) {
        if (level == AccessLevel.NONE) {
            throw new InternalException("staff directory needs level READ or WRITE");
        }
        return resolver.resolveStaff().entrySet().stream()
                .filter(staff -> staff.getValue().effective().get(section).allows(level))
                .filter(staff -> isActive(staff.getKey()))
                .map(staff -> new StaffRecipient(
                        staff.getKey(),
                        staff.getValue().roles().stream().map(RoleEntity::code).collect(Collectors.toSet())))
                .toList();
    }

    private boolean isActive(UUID accountId) {
        return clients.byAccountId(accountId)
                .map(card -> !card.blocked() && !card.erased())
                .orElse(true);
    }
}
