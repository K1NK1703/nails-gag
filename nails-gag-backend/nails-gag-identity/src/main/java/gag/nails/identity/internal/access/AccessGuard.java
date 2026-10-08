package gag.nails.identity.internal.access;

import gag.nails.clients.ClientQueries;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.identity.error.LastAccessAdminException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Правило последнего администратора (спецификация 4.3): в системе всегда есть хотя бы один аккаунт с
 * итоговым {@code ACCESS = WRITE}, чья карточка не заблокирована и не обезличена (аккаунт без карточки
 * считается активным). Проверяется после изменения, но до конца транзакции, под блокировкой строки
 * встроенной роли {@code DEVELOPER} ({@code SELECT … FOR UPDATE}) — одна точка сериализации для всех
 * изменений прав, блокировок и объединений, затрагивающих доступ: два одновременных понижения проходят
 * проверку по очереди, и второе видит результат первого.
 *
 * <p>Перед подсчётом накопленные изменения сбрасываются в базу, а гранты, переопределения и назначения
 * читаются запросами: считается то, что увидит фиксация, а не кэш сущностей. Строгая проверка
 * ({@link #requireAdminRemains()}) — из {@code AccessAdministration} (изменение и удаление роли, снятие
 * роли, переопределение) и {@code Accounts.delete} уходящего администратора; слушатели {@code
 * ClientBlocked} и {@code ClientMerged} прав не меняют и зовут {@link #requireAdminNotLost()}: до первого
 * разработчика администраторов нет, и блокировать клиентов это не мешает.
 */
@Component
public class AccessGuard {

    static final String DEVELOPER = "DEVELOPER";

    private final RoleRepository roles;
    private final AccountRoleRepository accountRoles;
    private final AccessOverrideRepository overrides;
    private final ClientQueries clients;

    AccessGuard(
            RoleRepository roles,
            AccountRoleRepository accountRoles,
            AccessOverrideRepository overrides,
            ClientQueries clients) {
        this.roles = roles;
        this.accountRoles = accountRoles;
        this.overrides = overrides;
        this.clients = clients;
    }

    /**
     * После изменения прав должен остаться активный администратор.
     *
     * @throws LastAccessAdminException не осталось ни одного активного администратора
     */
    public void requireAdminRemains() {
        lockAndCount(true);
    }

    /**
     * Для блокировки и объединения: права они не меняют, поэтому администратора можно потерять, только
     * если он был. Пока в системе никого с {@code ACCESS = WRITE} нет (до первого разработчика), терять
     * нечего — операция проходит.
     *
     * @throws LastAccessAdminException администраторы есть, но активного среди них не осталось
     */
    public void requireAdminNotLost() {
        lockAndCount(false);
    }

    private void lockAndCount(boolean strict) {
        roles.flush();
        roles.lockByCode(DEVELOPER).orElseThrow(() -> new InternalException("built-in role DEVELOPER is missing"));
        var candidates = adminCandidates();
        if (!strict && candidates.isEmpty()) {
            return;
        }
        if (candidates.stream().noneMatch(this::isActive)) {
            throw new LastAccessAdminException();
        }
    }

    /**
     * Аккаунты с итоговым {@code ACCESS = WRITE}: переопределение {@code WRITE} либо роль с {@code
     * WRITE} без переопределения раздела.
     */
    private List<UUID> adminCandidates() {
        var overridden = new HashMap<UUID, AccessLevel>();
        overrides.findAllByIdSection(Section.ACCESS).forEach(o -> overridden.put(o.accountId(), o.level()));
        var writeRoles = roles.findIdsGranting(Section.ACCESS, AccessLevel.WRITE);
        var candidates = new LinkedHashSet<UUID>();
        overridden.forEach((accountId, level) -> {
            if (level == AccessLevel.WRITE) {
                candidates.add(accountId);
            }
        });
        if (!writeRoles.isEmpty()) {
            accountRoles.findAllByIdRoleIdIn(writeRoles).stream()
                    .map(AccountRoleEntity::accountId)
                    .filter(accountId -> !overridden.containsKey(accountId))
                    .forEach(candidates::add);
        }
        return List.copyOf(candidates);
    }

    private boolean isActive(UUID accountId) {
        return clients.byAccountId(accountId)
                .map(card -> !card.blocked() && !card.erased())
                .orElse(true);
    }
}
