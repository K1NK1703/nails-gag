package gag.nails.identity.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

    Optional<SessionEntity> findByTokenHash(byte[] tokenHash);

    /** Возвращает число закрытых сессий. */
    long deleteAllByAccountId(UUID accountId);

    /**
     * Обслуживание: одним запросом удаляет сессии, открытые до {@code createdBefore} или не использованные
     * с {@code usedBefore}. Массовое удаление минует контекст персистентности, поэтому он сбрасывается до
     * запроса и очищается после.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SessionEntity s where s.createdAt < :createdBefore or s.lastUsedAt < :usedBefore")
    int deleteExpired(Instant createdBefore, Instant usedBefore);
}
