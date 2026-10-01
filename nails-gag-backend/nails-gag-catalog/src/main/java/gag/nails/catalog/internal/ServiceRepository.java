package gag.nails.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    List<ServiceEntity> findAllByOrderBySortOrderAscTitleAsc();

    List<ServiceEntity> findAllByActiveTrueOrderBySortOrderAscTitleAsc();

    List<ServiceEntity> findAllByActiveTrueAndShowOnLandingTrueOrderBySortOrderAscTitleAsc();

    List<ServiceEntity> findAllByIdIn(Collection<UUID> ids);
}
