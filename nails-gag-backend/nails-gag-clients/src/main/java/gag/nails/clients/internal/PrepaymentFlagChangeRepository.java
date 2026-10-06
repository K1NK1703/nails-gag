package gag.nails.clients.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PrepaymentFlagChangeRepository extends JpaRepository<PrepaymentFlagChangeEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<PrepaymentFlagChangeEntity> findAllByClientIdOrderByChangedAtDescIdDesc(UUID clientId);

    /** Последнее изменение в том же порядке, что и журнал. */
    Optional<PrepaymentFlagChangeEntity> findFirstByClientIdOrderByChangedAtDescIdDesc(UUID clientId);

    @Modifying(flushAutomatically = true)
    @Query("update PrepaymentFlagChangeEntity c set c.clientId = :to where c.clientId = :from")
    int reassign(UUID from, UUID to);
}
