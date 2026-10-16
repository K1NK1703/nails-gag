package gag.nails.identity.internal.login;

import gag.nails.identity.internal.session.SessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginEventRepository extends JpaRepository<LoginEventEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<LoginEventEntity> findAllByOrderByOccurredAtDescIdDesc(Limit limit);

    /** То же постранично; {@code pageable} без сортировки — порядок задан именем метода. */
    Page<LoginEventEntity> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);

    /**
     * Обслуживание: одним запросом удаляет события до {@code occurredBefore}. О сбросе и очистке контекста —
     * см. {@link SessionRepository#deleteExpired}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoginEventEntity e where e.occurredAt < :occurredBefore")
    int deleteOccurredBefore(Instant occurredBefore);
}
