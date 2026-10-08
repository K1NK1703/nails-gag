package gag.nails.identity.internal.access;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountRoleRepository extends JpaRepository<AccountRoleEntity, AccountRoleId> {

    List<AccountRoleEntity> findAllByIdAccountId(UUID accountId);

    List<AccountRoleEntity> findAllByIdRoleIdIn(Collection<UUID> roleIds);

    boolean existsByIdRoleId(UUID roleId);

    void deleteAllByIdAccountId(UUID accountId);

    /**
     * Назначает роль, если она ещё не назначена; возвращает 1, если строка добавлена, и 0, если уже была.
     * {@code ON CONFLICT} вместо проверки в коде: два одновременных назначения одной роли не прервут
     * транзакцию нарушением ключа — второе дождётся первого и ничего не вставит.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "insert into identity.account_role (account_id, role_id) values (:accountId, :roleId)"
                    + " on conflict on constraint account_role_pkey do nothing",
            nativeQuery = true)
    int insertIfAbsent(UUID accountId, UUID roleId);
}
