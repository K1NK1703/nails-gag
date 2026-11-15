package gag.nails.identity.model;

import java.util.Set;
import java.util.UUID;

/**
 * Аккаунт сотрудника из {@code StaffDirectory}.
 *
 * @param roleCodes коды назначенных ролей, например {@code "MASTER"}; пусто, если уровень дан только личным
 *     переопределением
 */
public record StaffRecipient(UUID accountId, Set<String> roleCodes) {

    public StaffRecipient {
        roleCodes = Set.copyOf(roleCodes);
    }
}
