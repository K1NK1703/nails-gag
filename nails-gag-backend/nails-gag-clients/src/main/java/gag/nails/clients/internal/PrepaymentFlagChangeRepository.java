package gag.nails.clients.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PrepaymentFlagChangeRepository extends JpaRepository<PrepaymentFlagChangeEntity, UUID> {

    List<PrepaymentFlagChangeEntity> findAllByClientIdOrderByChangedAtDesc(UUID clientId);

    @Modifying(flushAutomatically = true)
    @Query("update PrepaymentFlagChangeEntity c set c.clientId = :to where c.clientId = :from")
    int reassign(UUID from, UUID to);
}
