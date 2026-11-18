package gag.nails.identity.internal.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Строка аккаунта под блокировкой до конца транзакции: на ней встречаются вход, который после фиксации
     * перепроверяет блокировку карточки, и слушатель {@code ClientBlocked}, который закрывает сессии — кто
     * пришёл вторым, увидит результат первого. Hibernate 7 для PostgreSQL выполняет её как
     * {@code SELECT … FOR NO KEY UPDATE}: она конфликтует сама с собой, но не с {@code FOR KEY SHARE}
     * ({@code Accounts.lockExisting}) — для удаления аккаунта она не годится, там {@link #lockForDeletion}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> lockById(UUID id);

    /**
     * Строка аккаунта под блокировкой {@code FOR UPDATE} перед его удалением; пусто — аккаунта уже нет (под
     * READ COMMITTED ждавший получает пусто, когда удаление другой транзакцией зафиксировано). Запрос нативный:
     * {@code @Lock(PESSIMISTIC_WRITE)} Hibernate 7 превращает в {@code FOR NO KEY UPDATE}, а тот не конфликтует
     * с {@code FOR KEY SHARE}, которым модули, хранящие данные аккаунта, удерживают его на время записи
     * ({@code Accounts.lockExisting}). С {@code FOR UPDATE} удаление ждёт их фиксации, и синхронные слушатели
     * {@code AccountDeleted} видят записанное, а модули, пришедшие после удаления, — что аккаунта нет.
     */
    @Query(value = "select id from identity.account where id = :id for update", nativeQuery = true)
    Optional<UUID> lockForDeletion(UUID id);

    /** Аккаунты хотя бы с одной ролью или переопределением, по времени создания. */
    @Query("""
            select a from AccountEntity a
            where exists (select 1 from AccountRoleEntity ar where ar.id.accountId = a.id)
               or exists (select 1 from AccessOverrideEntity o where o.id.accountId = a.id)
            order by a.createdAt, a.id
            """)
    List<AccountEntity> findStaff();
}
