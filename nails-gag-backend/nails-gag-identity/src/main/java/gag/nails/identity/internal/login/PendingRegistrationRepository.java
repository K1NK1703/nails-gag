package gag.nails.identity.internal.login;

import gag.nails.identity.internal.session.SessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistrationEntity, UUID> {

    Optional<PendingRegistrationEntity> findByTicketHash(byte[] ticketHash);

    /** Удаляет билет одним запросом; 0 строк — его уже использовали. */
    @Modifying
    @Query("delete from PendingRegistrationEntity p where p.ticketHash = :ticketHash")
    int deleteByTicketHash(byte[] ticketHash);

    /**
     * Обслуживание: одним запросом удаляет билеты, выданные до {@code createdBefore}. О сбросе и очистке
     * контекста — см. {@link SessionRepository#deleteExpired}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PendingRegistrationEntity p where p.createdAt < :createdBefore")
    int deleteCreatedBefore(Instant createdBefore);
}
