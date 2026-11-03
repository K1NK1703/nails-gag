package gag.nails.finance.internal.recurring;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface RecurringRepository extends JpaRepository<RecurringEntity, UUID> {

    String ROW = "select new gag.nails.finance.internal.recurring.RecurringRow(r, c.name)"
            + " from RecurringEntity r join CategoryEntity c on c.id = r.categoryId";

    @Query(ROW + " order by r.dayOfMonth, r.createdAt, r.id")
    List<RecurringRow> findAllRows();

    @Query(ROW + " where r.id = :id")
    Optional<RecurringRow> findRowById(UUID id);

    /**
     * Шаблон под блокировкой строки ({@code SELECT … FOR UPDATE}): правка, пауза, удаление и ночная задача одного
     * шаблона идут по одной.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurringEntity r where r.id = :id")
    Optional<RecurringEntity> findLockedById(UUID id);

    /**
     * Следующий после {@code after} (по id) шаблон, которому ночной задаче есть что делать, — под блокировкой строки.
     * Есть что делать, если прогресс не дошёл до сегодняшнего дня и шаблон не закончился раньше прогресса. Шаблон,
     * строку которого держит правка, пропускается ({@code SKIP LOCKED}): правка сама создаёт его записи за
     * наступившие дни.
     */
    @Query(value = """
                    select id from finance.recurring
                    where id > :after
                      and (generated_through is null or generated_through < :today)
                      and (ends_on is null or generated_through is null or generated_through < ends_on)
                    order by id
                    limit 1
                    for update skip locked""", nativeQuery = true)
    Optional<UUID> lockNextDue(UUID after, LocalDate today);
}
