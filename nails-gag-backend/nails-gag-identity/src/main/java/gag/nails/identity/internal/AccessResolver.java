package gag.nails.identity.internal;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Итоговые уровни аккаунта по разделам (спецификация 4.3): личное переопределение, если оно есть; иначе
 * наибольший уровень среди ролей; иначе {@link AccessLevel#NONE}. Считается из базы при каждом
 * обращении: в сессии права не хранятся, изменение действует со следующего запроса.
 */
@Component
class AccessResolver {

    private final AccountRoleRepository accountRoles;
    private final RoleRepository roles;
    private final AccessOverrideRepository overrides;

    AccessResolver(AccountRoleRepository accountRoles, RoleRepository roles, AccessOverrideRepository overrides) {
        this.accountRoles = accountRoles;
        this.roles = roles;
        this.overrides = overrides;
    }

    /**
     * Роли, переопределения и итог аккаунта.
     *
     * @param roles по коду
     * @param overrides включая явный {@link AccessLevel#NONE}
     * @param effective все девять разделов, в порядке {@link Section}
     */
    record Resolved(List<RoleEntity> roles, Map<Section, AccessLevel> overrides, Map<Section, AccessLevel> effective) {}

    Resolved resolve(UUID accountId) {
        var roleIds = accountRoles.findAllByIdAccountId(accountId).stream()
                .map(AccountRoleEntity::roleId)
                .toList();
        var assigned = roles.findAllById(roleIds).stream()
                .sorted(Comparator.comparing(RoleEntity::code))
                .toList();
        var own = new EnumMap<Section, AccessLevel>(Section.class);
        overrides.findAllByIdAccountId(accountId).forEach(o -> own.put(o.section(), o.level()));
        return new Resolved(assigned, Collections.unmodifiableMap(own), effective(assigned, own));
    }

    Map<Section, AccessLevel> effective(UUID accountId) {
        return resolve(accountId).effective();
    }

    /** Итог по правилу спецификации для заданных ролей и переопределений; все девять разделов. */
    static Map<Section, AccessLevel> effective(Collection<RoleEntity> roles, Map<Section, AccessLevel> overrides) {
        var effective = new EnumMap<Section, AccessLevel>(Section.class);
        for (var section : Section.values()) {
            var override = overrides.get(section);
            if (override != null) {
                effective.put(section, override);
                continue;
            }
            var best = AccessLevel.NONE;
            for (var role : roles) {
                var level = role.grants().getOrDefault(section, AccessLevel.NONE);
                if (level.compareTo(best) > 0) {
                    best = level;
                }
            }
            effective.put(section, best);
        }
        return Collections.unmodifiableMap(effective);
    }
}
