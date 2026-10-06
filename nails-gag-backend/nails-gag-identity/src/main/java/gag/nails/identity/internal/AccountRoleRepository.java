package gag.nails.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRoleRepository extends JpaRepository<AccountRoleEntity, AccountRoleId> {

    List<AccountRoleEntity> findAllByIdAccountId(UUID accountId);

    boolean existsByIdRoleId(UUID roleId);
}
