package gag.nails.identity.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Строка аккаунта под блокировкой ({@code SELECT … FOR UPDATE}) до конца транзакции: на ней встречаются
     * вход, который после фиксации перепроверяет блокировку карточки, и слушатель {@code ClientBlocked},
     * который закрывает сессии — кто пришёл вторым, увидит результат первого.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> lockById(UUID id);

    /** Аккаунты хотя бы с одной ролью или переопределением, по времени создания. */
    @Query("""
            select a from AccountEntity a
            where exists (select 1 from AccountRoleEntity ar where ar.id.accountId = a.id)
               or exists (select 1 from AccessOverrideEntity o where o.id.accountId = a.id)
            order by a.createdAt, a.id
            """)
    List<AccountEntity> findStaff();
}
