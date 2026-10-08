package gag.nails.schedule.internal.template;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DayTemplateRepository extends JpaRepository<DayTemplateEntity, UUID> {

    List<DayTemplateEntity> findAllByOrderBySortOrderAscNameAsc();

    Optional<DayTemplateEntity> findByDefaultTemplateTrue();

    /** Последняя позиция; пусто, если шаблонов нет. */
    Optional<DayTemplateEntity> findFirstByOrderBySortOrderDesc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);

    /** Найденные шаблоны по идентификатору; отсутствующие идентификаторы в карту не попадают. */
    default Map<UUID, DayTemplateEntity> findByIds(Collection<UUID> ids) {
        return findAllById(ids).stream().collect(Collectors.toMap(DayTemplateEntity::id, Function.identity()));
    }
}
