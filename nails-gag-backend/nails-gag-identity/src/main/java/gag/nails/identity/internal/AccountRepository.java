package gag.nails.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /** Аккаунты хотя бы с одной ролью или переопределением, по времени создания. */
    @Query("""
            select a from AccountEntity a
            where exists (select 1 from AccountRoleEntity ar where ar.id.accountId = a.id)
               or exists (select 1 from AccessOverrideEntity o where o.id.accountId = a.id)
            order by a.createdAt, a.id
            """)
    List<AccountEntity> findStaff();
}
