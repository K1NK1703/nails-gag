package gag.nails.schedule.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface WeekTemplateRepository extends JpaRepository<WeekTemplateEntity, UUID> {

    List<WeekTemplateEntity> findAllByOrderByNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);

    /** Есть ли шаблон недели, который ссылается на этот шаблон дня. */
    @Query("select count(d) > 0 from WeekTemplateDayEntity d where d.dayTemplateId = :dayTemplateId")
    boolean usesDayTemplate(UUID dayTemplateId);

    /** Найденные шаблоны по идентификатору; отсутствующие идентификаторы в карту не попадают. */
    default Map<UUID, WeekTemplateEntity> findByIds(Collection<UUID> ids) {
        return findAllById(ids).stream().collect(Collectors.toMap(WeekTemplateEntity::id, Function.identity()));
    }
}
