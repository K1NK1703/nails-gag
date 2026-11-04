package gag.nails.finance.internal.entry;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface EntryChangeRepository extends JpaRepository<EntryChangeEntity, UUID> {

    /** Изменения записи от первого; при равных моментах — по id (UUIDv7 растёт со временем создания). */
    @Query("select c from EntryChangeEntity c where c.entryId = :entryId order by c.changedAt, c.id")
    List<EntryChangeEntity> findAllByEntryIdInOrder(UUID entryId);
}
