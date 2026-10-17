package gag.nails.api.internal.access;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/access/accounts/{id}/roles}: назначение роли аккаунту.
 *
 * @param roleId назначаемая роль; её нет — {@code 404 identity.role_not_found}
 */
record AssignRole(@NotNull UUID roleId) {}
