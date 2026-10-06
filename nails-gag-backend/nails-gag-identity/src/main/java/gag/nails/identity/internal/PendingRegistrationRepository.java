package gag.nails.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PendingRegistrationRepository extends JpaRepository<PendingRegistrationEntity, UUID> {

    Optional<PendingRegistrationEntity> findByTicketHash(byte[] ticketHash);
}
