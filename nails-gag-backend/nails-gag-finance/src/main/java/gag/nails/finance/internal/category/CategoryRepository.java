package gag.nails.finance.internal.category;

import gag.nails.finance.model.EntryKind;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {

    @Query("select c from CategoryEntity c where c.kind = :kind order by c.position")
    List<CategoryEntity> findAllByKindInOrder(EntryKind kind);

    /** Вид категории; пусто, если её нет. Вид не меняется, поэтому читается без блокировки. */
    @Query("select c.kind from CategoryEntity c where c.id = :id")
    Optional<EntryKind> findKindById(UUID id);

    /** Последняя позиция вида; пусто, если категорий вида нет. */
    @Query("select max(c.position) from CategoryEntity c where c.kind = :kind")
    Optional<Integer> findMaxPosition(EntryKind kind);

    /**
     * Сколько категорий вида носят это название без учёта регистра — 0 или 1. Сравнение — тем же {@code lower()}, что
     * и в уникальном индексе {@code category_kind_name_key}: проверка и индекс не расходятся.
     */
    @Query("select count(c) from CategoryEntity c where c.kind = :kind and lower(c.name) = lower(:name)")
    long countByName(EntryKind kind, String name);

    /** Как {@link #countByName}, но без категории {@code exceptId}: своё название в другом регистре не занято. */
    @Query("select count(c) from CategoryEntity c where c.kind = :kind and lower(c.name) = lower(:name)"
            + " and c.id <> :exceptId")
    long countByNameExcept(EntryKind kind, String name, UUID exceptId);

    /** Категория под блокировкой строки ({@code SELECT … FOR UPDATE}): изменения одной категории идут по одной. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CategoryEntity c where c.id = :id")
    Optional<CategoryEntity> findLockedById(UUID id);

    /**
     * Категория под разделяемой блокировкой строки ({@code SELECT … FOR SHARE}): запись, которая на неё ссылается,
     * проверяет видимость и берёт название, а скрытие и переименование ждут конца этой транзакции.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from CategoryEntity c where c.id = :id")
    Optional<CategoryEntity> findSharedById(UUID id);

    /** Категории вида под блокировкой строк в постоянном порядке (по id): наборы не блокируются накрест. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CategoryEntity c where c.kind = :kind order by c.id")
    List<CategoryEntity> findAllLockedByKind(EntryKind kind);

    /**
     * Откладывает проверку уникальности позиций до {@link #checkPositions()}: перестановка по пути повторяет позиции.
     */
    @Modifying
    @Query(value = "set constraints finance.category_kind_position_key deferred", nativeQuery = true)
    void deferPositionCheck();

    /**
     * Сбрасывает изменения и проверяет уникальность позиций сейчас, а не при фиксации: нарушение — ошибка внутри
     * метода, который переставлял, а не у того, кто фиксирует транзакцию.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "set constraints finance.category_kind_position_key immediate", nativeQuery = true)
    void checkPositions();
}
