package gag.nails.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessOverrideRepository extends JpaRepository<AccessOverrideEntity, AccessOverrideId> {

    List<AccessOverrideEntity> findAllByIdAccountId(UUID accountId);
}
