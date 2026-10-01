package gag.nails.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {

    List<ServiceEntity> findAllByOrderBySortOrderAscTitleAsc();

    List<ServiceEntity> findAllByActiveTrueOrderBySortOrderAscTitleAsc();

    List<ServiceEntity> findAllByActiveTrueAndShowOnLandingTrueOrderBySortOrderAscTitleAsc();

    /** Последняя позиция прайса; пусто, если каталог пуст. */
    Optional<ServiceEntity> findFirstByOrderBySortOrderDesc();

    /** Найденные услуги по идентификатору; отсутствующие идентификаторы в карту не попадают. */
    default Map<UUID, ServiceEntity> findByIds(Collection<UUID> ids) {
        return findAllById(ids).stream().collect(Collectors.toMap(ServiceEntity::id, Function.identity()));
    }
}
