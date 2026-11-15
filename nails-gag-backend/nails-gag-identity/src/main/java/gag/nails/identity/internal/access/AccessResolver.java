package gag.nails.identity.internal.access;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Итоговые уровни аккаунта по разделам (спецификация 4.3): личное переопределение, если оно есть; иначе
 * наибольший уровень среди ролей; иначе {@link AccessLevel#NONE}. Считается из базы при каждом
 * обращении: в сессии права не хранятся, изменение действует со следующего запроса.
 */
@Component
public class AccessResolver {

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
     * @param effective все разделы, в порядке {@link Section}
     */
    record Resolved(List<RoleEntity> roles, Map<Section, AccessLevel> overrides, Map<Section, AccessLevel> effective) {}

    Resolved resolve(UUID accountId) {
        var roleIds = accountRoles.findAllByIdAccountId(accountId).stream()
                .map(AccountRoleEntity::roleId)
                .toList();
        var own = new EnumMap<Section, AccessLevel>(Section.class);
        overrides.findAllByIdAccountId(accountId).forEach(o -> own.put(o.section(), o.level()));
        return resolved(roles.findAllById(roleIds), own);
    }

    public Map<Section, AccessLevel> effective(UUID accountId) {
        return resolve(accountId).effective();
    }

    /**
     * То же, что {@link #resolve(UUID)}, для всех аккаунтов хотя бы с одной ролью или переопределением,
     * по id: назначения, роли и переопределения читаются целиком несколькими запросами, а не по аккаунту.
     * У остальных аккаунтов итог по всем разделам — {@link AccessLevel#NONE}.
     */
    SortedMap<UUID, Resolved> resolveStaff() {
        var rolesById = roles.findAll().stream().collect(Collectors.toMap(RoleEntity::id, Function.identity()));
        var assigned = new HashMap<UUID, List<RoleEntity>>();
        accountRoles
                .findAll()
                .forEach(link -> assigned.computeIfAbsent(link.accountId(), id -> new ArrayList<>())
                        .add(rolesById.get(link.roleId())));
        var own = new HashMap<UUID, Map<Section, AccessLevel>>();
        overrides
                .findAll()
                .forEach(o -> own.computeIfAbsent(o.accountId(), id -> new EnumMap<>(Section.class))
                        .put(o.section(), o.level()));
        var accountIds = new TreeSet<UUID>(assigned.keySet());
        accountIds.addAll(own.keySet());
        var staff = new TreeMap<UUID, Resolved>();
        for (var accountId : accountIds) {
            staff.put(
                    accountId,
                    resolved(assigned.getOrDefault(accountId, List.of()), own.getOrDefault(accountId, Map.of())));
        }
        return Collections.unmodifiableSortedMap(staff);
    }

    private static Resolved resolved(Collection<RoleEntity> assigned, Map<Section, AccessLevel> own) {
        var sorted =
                assigned.stream().sorted(Comparator.comparing(RoleEntity::code)).toList();
        var overrides = new EnumMap<Section, AccessLevel>(Section.class);
        overrides.putAll(own);
        return new Resolved(sorted, Collections.unmodifiableMap(overrides), effective(sorted, overrides));
    }

    /** Итог по правилу спецификации для заданных ролей и переопределений; все разделы. */
    public static Map<Section, AccessLevel> effective(
            Collection<RoleEntity> roles, Map<Section, AccessLevel> overrides) {
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
