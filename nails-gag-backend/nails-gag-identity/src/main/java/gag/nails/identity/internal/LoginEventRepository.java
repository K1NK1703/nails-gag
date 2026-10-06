package gag.nails.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface LoginEventRepository extends JpaRepository<LoginEventEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<LoginEventEntity> findAllByOrderByOccurredAtDescIdDesc(Limit limit);

    /**
     * Обслуживание: одним запросом удаляет события до {@code occurredBefore}. О сбросе и очистке контекста —
     * см. {@link SessionRepository#deleteExpired}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoginEventEntity e where e.occurredAt < :occurredBefore")
    int deleteOccurredBefore(Instant occurredBefore);
}
