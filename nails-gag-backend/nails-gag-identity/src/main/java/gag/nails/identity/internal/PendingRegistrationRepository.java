package gag.nails.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PendingRegistrationRepository extends JpaRepository<PendingRegistrationEntity, UUID> {

    Optional<PendingRegistrationEntity> findByTicketHash(byte[] ticketHash);

    /**
     * Обслуживание: одним запросом удаляет билеты, выданные до {@code createdBefore}. О сбросе и очистке
     * контекста — см. {@link SessionRepository#deleteExpired}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingRegistrationEntity p where p.createdAt < :createdBefore")
    int deleteCreatedBefore(Instant createdBefore);
}
