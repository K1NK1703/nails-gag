package gag.nails.portfolio.internal.work;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface WorkRepository extends JpaRepository<WorkEntity, UUID> {

    /** Работа и строка согласия её клиента (если есть). */
    String WITH_CONSENT = " from WorkEntity w left join PublicationConsentEntity c on c.clientId = w.clientId";

    /** Правило публичности — то же, что {@link WorkRow#published()}. */
    String PUBLISHED = " where w.visible = true"
            + " and (w.source <> gag.nails.portfolio.model.WorkSource.CLIENT or c.clientId is not null)";

    String ROW = "select new gag.nails.portfolio.internal.work.WorkRow(w, c.clientId)" + WITH_CONSENT;

    /** Опубликованные работы по порядку (позиция уникальна); {@code total} — по тому же условию. */
    @Query(
            value = "select w" + WITH_CONSENT + PUBLISHED + " order by w.position",
            countQuery = "select count(w)" + WITH_CONSENT + PUBLISHED)
    Page<WorkEntity> findPublished(Pageable pageable);

    /** Сколько опубликованных работ с этим файлом: 0 или 1, файл у работы уникален. */
    @Query("select count(w)" + WITH_CONSENT + PUBLISHED + " and w.fileId = :fileId")
    long countPublishedByFileId(UUID fileId);

    @Query(ROW + " order by w.position")
    List<WorkRow> findAllRows();

    @Query(ROW + " where w.id = :workId")
    Optional<WorkRow> findRowById(UUID workId);

    /** Последняя позиция; пусто, если работ нет. */
    @Query("select max(w.position) from WorkEntity w")
    Optional<Integer> findMaxPosition();

    /**
     * Откладывает проверку уникальности позиций до {@link #checkPositions()}: перестановка по пути повторяет позиции.
     */
    @Modifying
    @Query(value = "set constraints portfolio.work_position_key deferred", nativeQuery = true)
    void deferPositionCheck();

    /**
     * Сбрасывает изменения и проверяет уникальность позиций сейчас, а не при фиксации: нарушение — ошибка внутри
     * метода, который переставлял, а не у того, кто фиксирует транзакцию.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "set constraints portfolio.work_position_key immediate", nativeQuery = true)
    void checkPositions();

    /**
     * Работа под блокировкой строки ({@code SELECT … FOR UPDATE}): параллельное изменение той же работы дождётся
     * конца транзакции и увидит её новое состояние.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkEntity w where w.id = :workId")
    Optional<WorkEntity> findLockedById(UUID workId);

    /**
     * Все работы под блокировкой строк в постоянном порядке (по id): транзакции, берущие пересекающиеся наборы,
     * не блокируют друг друга накрест.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkEntity w order by w.id")
    List<WorkEntity> findAllLocked();

    /** Работы клиента под блокировкой строк, в том же порядке, что {@link #findAllLocked()}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkEntity w where w.clientId = :clientId order by w.id")
    List<WorkEntity> findAllLockedByClientId(UUID clientId);
}
