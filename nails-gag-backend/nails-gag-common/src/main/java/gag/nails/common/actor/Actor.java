package gag.nails.common.actor;

import gag.nails.common.error.AccessDeniedException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Кто выполняет действие. Все команды публичных API модулей принимают {@code Actor}.
 *
 * <p>Клиент — не уровень и не роль: это {@code clientId} у вошедшего. Со своими данными клиент работает
 * всегда; доступ к чужим и к разделам админки задают уровни. Откуда уровни берутся (роли, личные
 * настройки) — дело модуля входа.
 *
 * @param accountId аккаунт входа; пусто у внутренних и тестовых акторов
 * @param clientId карточка клиента вошедшего; наружу использовать {@link #client()}
 * @param access уровни по разделам; отсутствующий раздел — {@link AccessLevel#NONE}
 */
public record Actor(@Nullable UUID accountId, @Nullable UUID clientId, Map<Section, AccessLevel> access) {

    private static final Map<Section, AccessLevel> MASTER_ACCESS =
            builtIn(AccessLevel.READ, AccessLevel.NONE, AccessLevel.WRITE);
    private static final Map<Section, AccessLevel> DEVELOPER_ACCESS =
            builtIn(AccessLevel.WRITE, AccessLevel.WRITE, AccessLevel.READ);

    public Actor {
        access = Map.copyOf(access);
    }

    public static Actor of(@Nullable UUID accountId, @Nullable UUID clientId, Map<Section, AccessLevel> access) {
        return new Actor(accountId, clientId, access);
    }

    /** Клиент без доступа к разделам. */
    public static Actor client(UUID clientId) {
        return new Actor(null, clientId, Map.of());
    }

    /** Набор встроенной роли «Мастер». */
    public static Actor master() {
        return new Actor(null, null, MASTER_ACCESS);
    }

    /** Набор встроенной роли «Разработчик». */
    public static Actor developer() {
        return new Actor(null, null, DEVELOPER_ACCESS);
    }

    /** Уровни встроенной роли «Мастер»: их же сеет миграция модуля входа. */
    public static Map<Section, AccessLevel> masterAccess() {
        return MASTER_ACCESS;
    }

    /** Уровни встроенной роли «Разработчик». */
    public static Map<Section, AccessLevel> developerAccess() {
        return DEVELOPER_ACCESS;
    }

    public AccessLevel level(Section section) {
        return access.getOrDefault(section, AccessLevel.NONE);
    }

    public boolean canRead(Section section) {
        return level(section).allows(AccessLevel.READ);
    }

    public boolean canWrite(Section section) {
        return level(section).allows(AccessLevel.WRITE);
    }

    /** Есть доступ хотя бы к одному разделу админки. */
    public boolean isStaff() {
        return access.values().stream().anyMatch(level -> level != AccessLevel.NONE);
    }

    public boolean isClient(UUID id) {
        return id.equals(clientId);
    }

    public Optional<UUID> client() {
        return Optional.ofNullable(clientId);
    }

    public void requireRead(Section section) {
        if (!canRead(section)) {
            throw new AccessDeniedException();
        }
    }

    public void requireWrite(Section section) {
        if (!canWrite(section)) {
            throw new AccessDeniedException();
        }
    }

    /** Сотрудник с чтением раздела видит любого клиента, клиент — только себя. */
    public void requireReadOrSelf(Section section, UUID clientId) {
        if (!canRead(section) && !isClient(clientId)) {
            throw new AccessDeniedException();
        }
    }

    /** Сотрудник с изменением раздела действует над любым клиентом, клиент — только над собой. */
    public void requireWriteOrSelf(Section section, UUID clientId) {
        if (!canWrite(section) && !isClient(clientId)) {
            throw new AccessDeniedException();
        }
    }

    public UUID requireClientId() {
        return client().orElseThrow(AccessDeniedException::new);
    }

    private static Map<Section, AccessLevel> builtIn(AccessLevel documents, AccessLevel access, AccessLevel finance) {
        var levels = new EnumMap<Section, AccessLevel>(Section.class);
        for (var section : Section.values()) {
            levels.put(section, AccessLevel.WRITE);
        }
        levels.put(Section.DOCUMENTS, documents);
        levels.put(Section.ACCESS, access);
        levels.put(Section.FINANCE, finance);
        levels.values().removeIf(level -> level == AccessLevel.NONE);
        return Map.copyOf(levels);
    }
}
