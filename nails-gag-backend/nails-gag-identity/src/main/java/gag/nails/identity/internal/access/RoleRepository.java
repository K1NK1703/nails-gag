package gag.nails.identity.internal.access;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    Optional<RoleEntity> findByCode(String code);

    List<RoleEntity> findAllByOrderByCodeAsc();

    /**
     * Строка роли под блокировкой ({@code SELECT … FOR UPDATE}) до конца транзакции — точка сериализации
     * для правила последнего администратора.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoleEntity r where r.code = :code")
    Optional<RoleEntity> lockByCode(String code);

    /** Роль под разделяемой блокировкой ({@code FOR SHARE}): назначение не пересечётся с удалением роли. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from RoleEntity r where r.id = :id")
    Optional<RoleEntity> lockForShare(UUID id);

    /** Роль под исключительной блокировкой ({@code FOR UPDATE}): удаление ждёт идущие назначения. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RoleEntity r where r.id = :id")
    Optional<RoleEntity> lockForUpdate(UUID id);

    /** Роли, дающие разделу ровно этот уровень — запросом к {@code role_grant}, а не из кэша сущностей. */
    @Query("select r.id from RoleEntity r join r.grants g where key(g) = :section and value(g) = :level")
    List<UUID> findIdsGranting(Section section, AccessLevel level);
}
