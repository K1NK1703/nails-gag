package gag.nails.identity.internal;

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
}
