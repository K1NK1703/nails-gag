package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.QuickCombo;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface EntryRepository extends JpaRepository<EntryEntity, UUID> {

    /** Запись и её категория. */
    String WITH_CATEGORY = " from EntryEntity e join CategoryEntity c on c.id = e.categoryId";

    String ROW = "select new gag.nails.finance.internal.entry.EntryRow(e, c.name)" + WITH_CATEGORY;

    /** Отбор {@link gag.nails.finance.model.EntryFilter}: пустой вид или категория — без отбора по ним. */
    String FILTER = " where e.occurredOn between :from and :to"
            + " and (:kind is null or e.kind = :kind)"
            + " and (:categoryId is null or e.categoryId = :categoryId)";

    @Query(ROW + " where e.id = :id")
    Optional<EntryRow> findRowById(UUID id);

    /** Новые дни первыми, внутри дня — позже внесённые; id замыкает порядок, чтобы страницы не перекрывались. */
    @Query(
            value = ROW + FILTER + " order by e.occurredOn desc, e.createdAt desc, e.id desc",
            countQuery = "select count(e) from EntryEntity e" + FILTER)
    Page<EntryRow> findPage(
            LocalDate from, LocalDate to, @Nullable EntryKind kind, @Nullable UUID categoryId, Pageable pageable);

    /**
     * Различные пары «категория + сумма» ручных записей вида с видимой категорией, от последней внесённой: момент
     * пары — момент самой поздней её записи. Записи шаблонов ({@code recurringId}) не в счёт. Сколько пар — задаёт
     * {@code pageable}.
     */
    @Query("select new gag.nails.finance.model.QuickCombo(c.id, c.name, e.amount)" + WITH_CATEGORY
            + " where e.kind = :kind and e.recurringId is null and c.hidden = false"
            + " group by c.id, c.name, e.amount"
            + " order by max(e.createdAt) desc, c.id, e.amount")
    List<QuickCombo> findRecentCombos(EntryKind kind, Pageable pageable);

    /**
     * Запись под блокировкой строки ({@code SELECT … FOR UPDATE}): параллельная правка или удаление той же записи
     * дождётся конца транзакции и увидит её новое состояние, а журнал — изменения строго по очереди.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EntryEntity e where e.id = :id")
    Optional<EntryEntity> findLockedById(UUID id);
}
