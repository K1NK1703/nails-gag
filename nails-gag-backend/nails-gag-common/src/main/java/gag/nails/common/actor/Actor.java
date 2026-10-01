package gag.nails.common.actor;

import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Кто выполняет действие. Все команды публичных API модулей принимают {@code Actor}.
 *
 * @param roles роли аккаунта, не пусто
 * @param clientId идентификатор клиента, если аккаунт вошёл как клиент; иначе {@code null}; наружу
 *     использовать {@link #client()}
 */
public record Actor(Set<Role> roles, @Nullable UUID clientId) {

    public Actor {
        if (roles.isEmpty()) {
            throw new ValidationException("roles", "must not be empty");
        }
        if (roles.contains(Role.CLIENT) && clientId == null) {
            throw new ValidationException("clientId", "required for role CLIENT");
        }
        roles = Set.copyOf(roles);
    }

    public static Actor client(UUID clientId) {
        return new Actor(Set.of(Role.CLIENT), clientId);
    }

    public static Actor master() {
        return new Actor(Set.of(Role.MASTER), null);
    }

    public static Actor developer() {
        return new Actor(Set.of(Role.DEVELOPER), null);
    }

    /** Мастер и разработчик выполняют операции мастера. */
    public boolean isMaster() {
        return roles.contains(Role.MASTER) || roles.contains(Role.DEVELOPER);
    }

    /** Клиент действует только над своими данными. */
    public boolean isClient(UUID id) {
        return roles.contains(Role.CLIENT) && id.equals(clientId);
    }

    public Optional<UUID> client() {
        return Optional.ofNullable(clientId);
    }

    public void requireMaster() {
        if (!isMaster()) {
            throw new AccessDeniedException();
        }
    }

    /** Мастер действует над любым клиентом, клиент — только над собой. */
    public void requireMasterOrSelf(UUID clientId) {
        if (!isMaster() && !isClient(clientId)) {
            throw new AccessDeniedException();
        }
    }

    public UUID requireClientId() {
        return client().orElseThrow(AccessDeniedException::new);
    }
}
