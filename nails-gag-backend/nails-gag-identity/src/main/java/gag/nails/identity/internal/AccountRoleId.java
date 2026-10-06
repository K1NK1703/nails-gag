package gag.nails.identity.internal;

import jakarta.persistence.Embeddable;
import java.util.UUID;

/** Составной ключ {@code account_role}. */
@Embeddable
record AccountRoleId(UUID accountId, UUID roleId) {}
