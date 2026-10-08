package gag.nails.booking.internal.booking;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ClientLockRepository extends JpaRepository<ClientLockEntity, UUID> {

    /**
     * Заводит строку клиента, если её ещё нет. ON CONFLICT вместо проверки в коде: параллельная
     * вставка той же строки дождётся конца чужой транзакции и не прервёт свою нарушением ключа.
     */
    @Modifying
    @Query(
            value = "insert into booking.client_lock (id) values (:clientId) on conflict (id) do nothing",
            nativeQuery = true)
    void ensure(UUID clientId);

    /** Строка клиента под блокировкой (SELECT … FOR UPDATE) до конца транзакции. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ClientLockEntity l where l.id = :clientId")
    Optional<ClientLockEntity> lock(UUID clientId);
}
