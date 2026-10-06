package gag.nails.identity.internal;

import gag.nails.common.actor.Section;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccessOverrideRepository extends JpaRepository<AccessOverrideEntity, AccessOverrideId> {

    List<AccessOverrideEntity> findAllByIdAccountId(UUID accountId);

    List<AccessOverrideEntity> findAllByIdSection(Section section);

    void deleteAllByIdAccountId(UUID accountId);
}
