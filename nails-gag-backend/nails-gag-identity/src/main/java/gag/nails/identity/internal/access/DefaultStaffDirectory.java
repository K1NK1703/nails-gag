package gag.nails.identity.internal.access;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.identity.StaffDirectory;
import gag.nails.identity.model.StaffRecipient;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Итоговые уровни — {@link AccessResolver}, тем же правилом, что у {@code Actor} сессии; уровень {@link
 * AccessLevel#NONE} у всех остальных аккаунтов, поэтому кандидаты — только аккаунты с ролями или
 * переопределениями. Карточка проверяется уже после фильтра по уровню, отдельным запросом на аккаунт:
 * сотрудников единицы. Неактивный аккаунт ({@link ActiveAccounts}) исключается, как и в правиле последнего
 * администратора.
 */
@Service
@Transactional(readOnly = true)
class DefaultStaffDirectory implements StaffDirectory {

    private final AccessResolver resolver;
    private final ActiveAccounts active;

    DefaultStaffDirectory(AccessResolver resolver, ActiveAccounts active) {
        this.resolver = resolver;
        this.active = active;
    }

    @Override
    public List<StaffRecipient> recipients(Section section, AccessLevel level) {
        if (level == AccessLevel.NONE) {
            throw new InternalException("staff directory needs level READ or WRITE");
        }
        return resolver.resolveStaff().entrySet().stream()
                .filter(staff -> staff.getValue().effective().get(section).allows(level))
                .filter(staff -> active.isActive(staff.getKey()))
                .map(staff -> new StaffRecipient(
                        staff.getKey(),
                        staff.getValue().roles().stream().map(RoleEntity::code).collect(Collectors.toSet())))
                .toList();
    }
}
