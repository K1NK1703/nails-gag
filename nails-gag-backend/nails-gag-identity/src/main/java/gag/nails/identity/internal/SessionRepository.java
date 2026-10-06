package gag.nails.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

    Optional<SessionEntity> findByTokenHash(byte[] tokenHash);

    /** Возвращает число закрытых сессий. */
    long deleteAllByAccountId(UUID accountId);
}
