package gag.nails.clients.internal;

import gag.nails.clients.ConsentKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ConsentRepository extends JpaRepository<ConsentEntity, UUID> {

    List<ConsentEntity> findAllByClientIdOrderByGivenAtDesc(UUID clientId);

    boolean existsByClientIdAndKindAndDocumentVersion(UUID clientId, ConsentKind kind, String documentVersion);

    /** Без clearAutomatically: сущности клиентов, загруженные в той же транзакции, остаются управляемыми. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentEntity c set c.clientId = :to where c.clientId = :from")
    int reassign(UUID from, UUID to);
}
